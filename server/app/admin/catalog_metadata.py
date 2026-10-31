"""Domestic EDA previews; only explicitly selected groups enter sync transactions."""
from __future__ import annotations

from datetime import datetime, timezone
from dataclasses import replace
from decimal import Decimal, InvalidOperation
import json
import re
import sqlite3
from typing import Literal
from urllib.parse import urlencode
from urllib.request import Request as UrlRequest, urlopen

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from ..accounts import Account
from ..auth import require_token
from ..config import Settings, get_settings
from ..database import get_db
from ..schemas import ComponentPayload, PushRequest
from ..storage import load_allocations
from .operations import (AdminOperationConflict, AdminOperationNotFound,
                         _component_row, _now_datetime_after, _require_version, _retry_target,
                         _save_payload, _save_receipt, _storage_time)
from .order_metadata import parameters_from_description, PARAMETER_LINE
from .spec_enrichment import EnrichmentJob, EnrichmentJobs

router = APIRouter(prefix="/components/catalog-metadata", dependencies=[Depends(require_token)])
SKU = re.compile(r"C[0-9]{1,10}\Z")
PRICE_PREFIXES = ("参考单价：", "参考币种：", "参考起订量：", "价格来源：", "价格查询时间：")
INTERNAL = {"Symbol", "Footprint", "Designator", "3D Model", "3D Model Title", "3D Model Transform",
            "Add into BOM", "Convert to PCB", "Name", "Supplier", "Supplier Part", "Supplier Footprint",
            "Manufacturer", "Manufacturer Part", "LCSC Part Name", "Datasheet", "JLCPCB Part Class"}
FieldName = Literal["category", "package_name", "parameters", "reference_price"]


def _line(value: object) -> str:
    return value.strip() if isinstance(value, str) and "\n" not in value and "\r" not in value else ""


def parse_catalog(sku: str, data: dict) -> dict | None:
    if data.get("code") != 200:
        raise ValueError("Unexpected domestic EDA response")
    products = data.get("result", {}).get("productList", [])
    product = next((item for item in products if item.get("code") == sku), None)
    if product is None:
        return None
    attributes = (product.get("device_info") or {}).get("attributes") or {}
    parameters = {key: _line(value) for key, value in attributes.items()
                  if key not in INTERNAL and _line(key) and _line(value) and _line(value) != "-"}
    product_id = str(product.get("id", ""))
    source = (f"https://item.szlcsc.com/{product_id}.html" if product_id.isascii() and product_id.isdigit()
              else "https://so.szlcsc.com/global.html?" + urlencode({"k": sku}))
    price = None
    tiers = []
    if product.get("isShowPrice", True):
        for tier in product.get("priceList") or []:
            try:
                minimum = Decimal(str(tier.get("startNumber")))
                amount = Decimal(str(tier.get("price")))
                if minimum.is_finite() and amount.is_finite() and minimum > 0 and minimum == int(minimum) and amount > 0:
                    tiers.append((int(minimum), amount))
            except (InvalidOperation, ValueError, TypeError, OverflowError):
                continue
    if tiers:
        minimum, amount = min(tiers, key=lambda value: value[0])
        # The official domestic UI renders priceList.price with ￥ and startNumber +.
        price = {"unit_price": format(amount.normalize(), "f"), "currency": "CNY", "minimum_quantity": minimum,
                 "source_url": source, "queried_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}
    return {"category": _line(product.get("catalogName")), "package_name": _line(product.get("standard")),
            "parameters": parameters, "reference_price": price, "source_url": source}


def lookup_catalog(sku: str) -> dict | None:
    url = "https://pro.lceda.cn/api/eda/product/search?" + urlencode({"keyword": sku, "currPage": 1, "pageSize": 20})
    request = UrlRequest(url, headers={"Accept": "application/json", "User-Agent": "ComponentVault/0.7"})
    with urlopen(request, timeout=6) as response:
        body = response.read(2 * 1024 * 1024 + 1)
    if len(body) > 2 * 1024 * 1024:
        raise ValueError("Domestic EDA response exceeds 2 MiB")
    return parse_catalog(sku, json.loads(body))


def price_from_description(description: str) -> dict | None:
    values = {prefix: next((line[len(prefix):] for line in reversed(description.splitlines()) if line.startswith(prefix)), "") for prefix in PRICE_PREFIXES}
    if not values[PRICE_PREFIXES[0]] or not values[PRICE_PREFIXES[1]]:
        return None
    return {"unit_price": values[PRICE_PREFIXES[0]], "currency": values[PRICE_PREFIXES[1]],
            "minimum_quantity": int(values[PRICE_PREFIXES[2]]) if values[PRICE_PREFIXES[2]].isdigit() else None,
            "source_url": values[PRICE_PREFIXES[3]], "queried_at": values[PRICE_PREFIXES[4]]}


def merge_groups(description: str, changes: dict) -> str:
    # Preserve every non-owned line verbatim, including whitespace and line endings.
    lines = description.splitlines(keepends=True)
    if "parameters" in changes:
        lines = [line for line in lines if not PARAMETER_LINE.fullmatch(line.rstrip("\r\n"))]
    if changes.get("reference_price"):
        lines = [line for line in lines if not line.startswith(PRICE_PREFIXES)]
    result = "".join(lines)
    additions = []
    if "parameters" in changes:
        additions.extend(f"参数：{key}：{value}" for key, value in changes["parameters"].items())
    if changes.get("reference_price"):
        price = changes["reference_price"]
        values = [price["unit_price"], price["currency"], price.get("minimum_quantity"), price["source_url"], price["queried_at"]]
        additions.extend(f"{prefix}{value}" for prefix, value in zip(PRICE_PREFIXES, values) if value is not None)
    if additions:
        result += ("" if not result or result.endswith(("\n", "\r")) else "\n") + "\n".join(additions)
    return result


def inventory_items(connection: sqlite3.Connection) -> list[dict]:
    return [dict(row) for row in connection.execute(
        "SELECT id, sku, name, category, package_name, description, updated_at FROM components WHERE deleted = 0 ORDER BY sku, id"
    )]


def scan_preview(job: EnrichmentJob) -> None:
    cache: dict[str, tuple[dict | None, str]] = {}
    for index, item in enumerate(job.items):
        if job.cancel.is_set():
            return
        if item.get("status") in {"ready", "unchanged", "skipped"}:
            with job.lock:
                job.processed += 1
                job.skipped += item["status"] in {"unchanged", "skipped"}
            continue
        sku = str(item["sku"]).strip().upper()
        state, reason, changes = "skipped", "invalid_sku", {}
        if SKU.fullmatch(sku):
            if sku not in cache:
                if cache and job.cancel.wait(0.3):
                    return
                try:
                    cache[sku] = lookup_catalog(sku), ""
                except Exception as error:
                    cache[sku] = None, f"{type(error).__name__}: {error}"
            candidate, failure = cache[sku]
            if failure:
                state, reason = "failed", failure
            elif candidate is None:
                reason = "no_exact_match"
            else:
                for field in ("category", "package_name"):
                    if candidate[field] and candidate[field] != item[field]:
                        changes[field] = {"before": item[field], "after": candidate[field]}
                before = parameters_from_description(item.get("description") or "")
                if candidate["parameters"] and candidate["parameters"] != before:
                    changes["parameters"] = {"before": before, "after": candidate["parameters"]}
                old_price = price_from_description(item.get("description") or "")
                new_price = candidate["reference_price"]
                if new_price and (not old_price or any(old_price.get(key) != new_price.get(key) for key in ("unit_price", "currency", "minimum_quantity", "source_url"))):
                    changes["reference_price"] = {"before": old_price, "after": new_price}
                state, reason = ("ready", "") if changes else ("unchanged", "no_changes")
        with job.lock:
            job.items[index].update(status=state, reason=reason, changes=changes)
            job.processed += 1
            job.failed += state == "failed"
            job.skipped += state in {"skipped", "unchanged"}


def _manager(request: Request) -> EnrichmentJobs:
    return request.app.state.spec_enrichment_jobs


def _job(request: Request, account: Account, job_id: str) -> EnrichmentJob:
    job = _manager(request).get(job_id, account)
    if job is None or not job.preview:
        raise HTTPException(404, "Catalog preview not found. Analyze again after a server restart.")
    return job


@router.post("/jobs", status_code=202)
def start(request: Request, account: Account = Depends(require_token), settings: Settings = Depends(get_settings), connection: sqlite3.Connection = Depends(get_db)) -> dict:
    try:
        return _manager(request).start(account, settings, inventory_items(connection), preview=True).snapshot()
    except ValueError as error:
        raise HTTPException(409, str(error)) from error


@router.get("/jobs/active")
def active(request: Request, account: Account = Depends(require_token)) -> dict | None:
    job = _manager(request).active(account, preview=True)
    return job.snapshot() if job else None


@router.get("/jobs/{job_id}")
def status(job_id: str, request: Request, account: Account = Depends(require_token)) -> dict:
    return _job(request, account, job_id).snapshot()


@router.get("/jobs/{job_id}/rows")
def rows(job_id: str, request: Request, page: int = Query(1, ge=1), account: Account = Depends(require_token)) -> dict:
    job = _job(request, account, job_id)
    with job.lock:
        selected = job.items[(page - 1) * 50:page * 50]
        return {"page": page, "total": len(job.items), "page_size": 50, "items": [
            {key: item.get(key) for key in ("id", "sku", "name", "updated_at", "status", "reason", "changes")} for item in selected]}


@router.post("/jobs/{job_id}/cancel")
def cancel(job_id: str, request: Request, account: Account = Depends(require_token)) -> dict:
    job = _job(request, account, job_id)
    job.cancel.set()
    return job.snapshot()


@router.post("/jobs/{job_id}/retry", status_code=202)
def retry(job_id: str, request: Request, account: Account = Depends(require_token), settings: Settings = Depends(get_settings), connection: sqlite3.Connection = Depends(get_db)) -> dict:
    job = _job(request, account, job_id)
    if job.state not in {"completed", "cancelled"}:
        raise HTTPException(409, "Wait for analysis to stop.")
    current = {item["id"]: item for item in inventory_items(connection)}
    if not any(item["status"] in {"failed", "pending"} and item["id"] in current for item in job.items):
        raise HTTPException(409, "No failed or pending rows to retry.")
    items = [current[item["id"]] if item["status"] in {"failed", "pending"} and item["id"] in current else item.copy() for item in job.items]
    try:
        return _manager(request).start(account, settings, items, preview=True).snapshot()
    except ValueError as error:
        raise HTTPException(409, str(error)) from error


@router.post("/jobs/{job_id}/clear")
def clear(job_id: str, request: Request, account: Account = Depends(require_token)) -> dict:
    _job(request, account, job_id)
    try:
        return {"cleared": _manager(request).clear(job_id, account)}
    except ValueError as error:
        raise HTTPException(409, str(error)) from error


class Selection(BaseModel):
    model_config = ConfigDict(extra="forbid")
    component_id: str
    fields: list[FieldName] = Field(min_length=1, max_length=4)
    request_id: str = Field(min_length=8, max_length=120)


class ApplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[Selection] = Field(min_length=1, max_length=50)


def apply_one(connection: sqlite3.Connection, settings: Settings, row: dict, selection: Selection) -> str:
    selected = {key: row["changes"][key]["after"] for key in set(selection.fields) if key in row["changes"]}
    if not selected or len(selected) != len(set(selection.fields)):
        raise ValueError("Selected field is not in the preview")
    payload = json.dumps({"id": row["id"], "version": row["updated_at"], "changes": selected}, sort_keys=True, ensure_ascii=False)
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        if _retry_target(connection, selection.request_id, "catalog_metadata", row["id"], payload):
            return "unchanged"
        current = _component_row(connection, row["id"])
        if current["sku"].strip().upper() != row["sku"].strip().upper():
            raise AdminOperationConflict("SKU changed; analyze again.")
        _require_version(current, datetime.fromisoformat(row["updated_at"].replace("Z", "+00:00")))
        description = merge_groups(current["description"] or "", selected)
        category, package = selected.get("category", current["category"]), selected.get("package_name", current["package_name"])
        managed = bool(current["inventory_managed"])
        now = _now_datetime_after(current["updated_at"])
        component = ComponentPayload(id=current["id"], sku=current["sku"], name=current["name"], category=category,
            package_name=package, description=description, location=current["location"], quantity=current["quantity"],
            min_stock=current["min_stock"], updated_at=now, allocations=load_allocations(connection, current["id"]) if managed else None,
            base_updated_at=datetime.fromisoformat(row["updated_at"].replace("Z", "+00:00")) if managed else None)
        _save_payload(connection, settings, PushRequest(device_id="admin-web", inventory_protocol=1 if managed else 0, components=[component]))
        _save_receipt(connection, selection.request_id, "catalog_metadata", row["id"], payload, _storage_time(now))
    return "updated"


@router.post("/jobs/{job_id}/apply")
def apply(job_id: str, draft: ApplyRequest, request: Request, account: Account = Depends(require_token), settings: Settings = Depends(get_settings), connection: sqlite3.Connection = Depends(get_db)) -> dict:
    if not settings.web_inventory_enabled:
        raise HTTPException(403, "Web inventory writes are disabled.")
    job = _job(request, account, job_id)
    if job.state not in {"completed", "cancelled"}:
        raise HTTPException(409, "Wait for analysis to stop.")
    lookup = {item["id"]: item for item in job.items if item["status"] == "ready"}
    counts = {item.component_id: sum(other.component_id == item.component_id for other in draft.items) for item in draft.items}
    results = []
    account_settings = settings if account.role == "admin" else replace(settings, mqtt_enabled=False)
    for selection in draft.items:
        message = ""
        try:
            if counts[selection.component_id] > 1 or selection.component_id not in lookup:
                raise ValueError("Select each preview row once")
            state = apply_one(connection, account_settings, lookup[selection.component_id], selection)
        except AdminOperationConflict as error:
            state, message = "conflict", str(error)
        except (AdminOperationNotFound, ValueError, sqlite3.IntegrityError) as error:
            state, message = "failed", str(error)
        results.append({"component_id": selection.component_id, "status": state, "message": message})
    return {"items": results, "summary": {state: sum(item["status"] == state for item in results) for state in ("updated", "unchanged", "conflict", "failed")}}
