"""Account-scoped EDA previews with explicitly selected metadata updates."""
from __future__ import annotations

from datetime import datetime, timezone
from dataclasses import replace
from collections import Counter
from decimal import Decimal, InvalidOperation
import json
import re
import sqlite3
from typing import Iterable, Literal
import unicodedata
from urllib.error import HTTPError, URLError
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
from .order_metadata import PARAMETER_LINE, _parameter_identity
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


def _products(data: dict, domestic: bool) -> list[dict]:
    if data.get("code") != (200 if domestic else 0):
        raise ValueError("Unexpected EDA response")
    products = (data.get("result") or {}).get("productList") or []
    if not isinstance(products, list):
        raise ValueError("Invalid EDA product list")
    return products


def _product_metadata(product: dict, sku: str, domestic: bool) -> dict:
    attributes = (product.get("device_info") or {}).get("attributes") or {}
    parameters = {key: _line(value) for key, value in attributes.items()
                  if key not in INTERNAL and _line(key) and _line(value) and _line(value) != "-"}
    product_id = str(product.get("id", ""))
    source = (f"https://item.szlcsc.com/{product_id}.html" if product_id.isascii() and product_id.isdigit()
              else "https://so.szlcsc.com/global.html?" + urlencode({"k": sku}))
    if not domestic:
        source = f"https://www.lcsc.com/product-detail/{sku}.html"
    price = None
    tiers = []
    if domestic and product.get("isShowPrice", True):
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
    tags = ((product.get("device_info") or {}).get("tags") or {}).get("child_tag") or {}
    return {"category": _line(product.get("catalogName")) if domestic else _line(tags.get("name")),
            "package_name": _line(product.get("standard" if domestic else "package")) or _line(attributes.get("Supplier Footprint")),
            "parameters": parameters, "reference_price": price, "source_url": source,
            "match_model": _line(product.get("model" if domestic else "mpn")) or _line(attributes.get("Manufacturer Part")),
            "match_brand": _line(product.get("brandName" if domestic else "manufacturer")) or _line(attributes.get("Manufacturer"))}


def parse_catalog(sku: str, data: dict, domestic: bool = True) -> dict | None:
    field = "code" if domestic else "number"
    product = next((item for item in _products(data, domestic) if item.get(field) == sku), None)
    return _product_metadata(product, sku, domestic) if product is not None else None


def _fetch_catalog(keyword: str, domestic: bool) -> dict:
    host = "pro.lceda.cn" if domestic else "pro.easyeda.com"
    url = f"https://{host}/api/eda/product/search?" + urlencode({"keyword": keyword, "currPage": 1, "pageSize": 20})
    request = UrlRequest(url, headers={"Accept": "application/json", "User-Agent": "ComponentVault/0.7"})
    with urlopen(request, timeout=6) as response:
        body = response.read(2 * 1024 * 1024 + 1)
    if len(body) > 2 * 1024 * 1024:
        raise ValueError("EDA response exceeds 2 MiB")
    return json.loads(body)


def lookup_catalog(sku: str, domestic: bool = True) -> dict | None:
    return parse_catalog(sku, _fetch_catalog(sku, domestic), domestic)


def _identity(value: str) -> str:
    return "".join(unicodedata.normalize("NFKC", value).casefold().split())


def _brand(value: str) -> str:
    key = _identity(value).partition("(")[0]
    return {"国巨": "yageo", "三星": "samsung", "三星电机": "samsung",
            "罗姆": "rohm"}.get(key, key)


def lookup_model(model: str, brand: str, package: str, domestic: bool) -> dict | None:
    field = "code" if domestic else "number"
    matches = {}
    for product in _products(_fetch_catalog(model, domestic), domestic):
        sku = str(product.get(field, ""))
        if not SKU.fullmatch(sku):
            continue
        candidate = _product_metadata(product, sku, domestic)
        if _identity(candidate["match_model"]) != _identity(model) or _brand(candidate["match_brand"]) != _brand(brand):
            continue
        if package and _identity(candidate["package_name"]) != _identity(package):
            continue
        matches[sku] = candidate
    return next(iter(matches.values())) if len(matches) == 1 else None


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
    parameters = changes.get("parameters", {})
    if parameters:
        def retained(line: str) -> bool:
            match = PARAMETER_LINE.fullmatch(line.rstrip("\r\n"))
            return match is None or _catalog_parameter_identity(match[1], match[2])[0] not in parameters
        lines = [line for line in lines if retained(line)]
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


def _electrical(key: str, value: str) -> tuple[str, Decimal] | None:
    units = {"阻值": "Ω", "容量": "F", "电感量": "H", "耐压": "V", "精度": "%", "功率": "W", "额定电流": "A", "频率": "Hz"}
    unit = units.get(key)
    if unit is None:
        return None
    text = "".join(unicodedata.normalize("NFKC", value).split()).replace("μ", "u").replace("±", "")
    if key == "阻值":
        marking = re.fullmatch(r"(\d+)([RrKkMm])(\d*)", text)
        if marking:
            whole, prefix, fraction = marking.groups()
            text = whole + (f".{fraction}" if fraction else "") + ("" if prefix in "Rr" else prefix) + "Ω"
        text = re.sub(r"ohms?$", "Ω", text, flags=re.I)
    if key == "耐压":
        text = re.sub(r"(?<=V)DC$", "", text, flags=re.I)
    match = re.fullmatch(rf"(\d+(?:\.\d+)?)([pnumkKMGT]?){re.escape(unit)}", text, flags=re.I)
    if not match:
        return None
    scale = {"": 0, "p": -12, "n": -9, "u": -6, "m": -3, "k": 3, "K": 3, "M": 6, "G": 9, "T": 12}
    exponent = scale.get(match[2])
    return (unit, Decimal(match[1]).scaleb(exponent)) if exponent is not None else None


def _catalog_parameter_identity(label: str, value: str) -> tuple[str, str]:
    key, value = _parameter_identity(label, value)
    if key.casefold() == "value":
        key = next((kind for kind in ("阻值", "容量", "电感量") if _electrical(kind, value) is not None), key)
    return key, value


def _parameter_values(parameters: Iterable[tuple[str, str]]) -> tuple[dict[str, str], set[str]]:
    result = {}
    ambiguous = set()
    for label, value in parameters:
        key, value = _catalog_parameter_identity(label, value)
        if not value:
            continue
        if key in result and not _same_parameter(key, result[key], value):
            ambiguous.add(key)
        result[key] = value
    return {key: value for key, value in result.items() if key not in ambiguous}, ambiguous


def _saved_parameters(description: str) -> tuple[dict[str, str], set[str]]:
    matches = (PARAMETER_LINE.fullmatch(line) for line in description.splitlines())
    return _parameter_values((match[1], match[2]) for match in matches if match is not None)


def _same_parameter(key: str, before: str, after: str) -> bool:
    if unicodedata.normalize("NFKC", before).strip() == unicodedata.normalize("NFKC", after).strip():
        return True
    left, right = _electrical(key, before), _electrical(key, after)
    return left is not None and left == right


def _stored_identity(description: str) -> tuple[str, str]:
    values = {}
    for line in description.splitlines():
        key, _, value = re.sub(r"：", ":", line, count=1).partition(":")
        key = key.strip().casefold()
        if key in {"型号", "model", "品牌", "brand"} and value.strip():
            canonical = "model" if key in {"型号", "model"} else "brand"
            values.setdefault(canonical, set()).add(value.strip())
    def one(key: str) -> str:
        candidates = values.get(key, set())
        return next(iter(candidates)) if len(candidates) == 1 else ""
    return one("model"), one("brand")


def _is_missing(value: str) -> bool:
    return _identity(value) in {"", "-", "未分类", "uncategorized", "unknown", "未知"}


def _preview_changes(item: dict, candidate: dict, review: bool) -> dict:
    changes = {}
    for field in ("category", "package_name"):
        before, after = item[field], candidate[field]
        if after and _identity(after) != _identity(before or "") and (
            review or _is_missing(before or "")
        ):
            changes[field] = {"before": before, "after": after}
    description = item.get("description") or ""
    before, ambiguous = _saved_parameters(description)
    after, _ = _parameter_values(candidate["parameters"].items())
    proposed = {key: value for key, value in after.items()
                if key not in ambiguous and (key not in before or review and not _same_parameter(key, before[key], value))}
    if proposed:
        changes["parameters"] = {"before": {key: before[key] for key in proposed if key in before},
                                 "after": proposed}
    old_price = price_from_description(description)
    new_price = candidate["reference_price"]
    if new_price and (not old_price or review and any(old_price.get(key) != new_price.get(key)
        for key in ("unit_price", "currency", "minimum_quantity", "source_url"))):
        changes["reference_price"] = {"before": old_price, "after": new_price}
    return changes


def _identity_conflicts(item: dict, candidate: dict) -> bool:
    before, ambiguous = _saved_parameters(item.get("description") or "")
    after, _ = _parameter_values(candidate["parameters"].items())
    for kind in ("阻值", "容量", "电感量", "耐压", "精度"):
        if kind in ambiguous and kind in after:
            return True
        old, new = _electrical(kind, before.get(kind, "")), _electrical(kind, after.get(kind, ""))
        if old is not None and new is not None and old != new:
            return True
    return False


def inventory_items(connection: sqlite3.Connection) -> list[dict]:
    return [dict(row) for row in connection.execute(
        "SELECT id, sku, name, category, package_name, description, updated_at FROM components WHERE deleted = 0 ORDER BY sku, id"
    )]


def scan_preview(job: EnrichmentJob) -> None:
    cache: dict[tuple, tuple[dict | None, str]] = {}
    rate_limited = False
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
        match_source = "sku" if SKU.fullmatch(sku) else "mpn"
        model, brand = _stored_identity(item.get("description") or "")
        package = item["package_name"] or ""
        if _is_missing(package):
            package = ""
        domestic = item.get("_language", "zh-CN") == "zh-CN"
        key = (match_source, sku, domestic) if match_source == "sku" else (match_source, model, brand, package, domestic)
        candidate = None
        if match_source == "sku" or (model and brand):
            if rate_limited and key not in cache:
                cache[key] = None, "upstream_rate_limited"
            elif key not in cache:
                if cache and job.cancel.wait(0.3):
                    return
                try:
                    if match_source == "sku":
                        candidate = lookup_catalog(sku) if domestic else lookup_catalog(sku, False)
                    else:
                        candidate = lookup_model(model, brand, package, domestic)
                    cache[key] = candidate, ""
                except HTTPError as error:
                    rate_limited = error.code == 429
                    cache[key] = None, "upstream_rate_limited" if rate_limited else "upstream_response"
                except (URLError, TimeoutError, OSError):
                    cache[key] = None, "upstream_network"
                except (ValueError, AttributeError, TypeError):
                    cache[key] = None, "upstream_response"
            candidate, failure = cache[key]
            if failure:
                state, reason = "failed", failure
            elif candidate is None:
                reason = "no_exact_match"
            elif match_source == "mpn" and _identity_conflicts(item, candidate):
                reason = "identity_conflict"
            elif not any(candidate[field] for field in ("category", "package_name", "parameters", "reference_price")):
                reason = "no_catalog_data"
            else:
                changes = _preview_changes(item, candidate, item.get("_mode") == "review")
                state, reason = ("ready", "") if changes else ("unchanged", "no_changes")
        with job.lock:
            job.items[index].update(status=state, reason=reason, changes=changes)
            if candidate:
                job.items[index].update(match_source=match_source, source_url=candidate["source_url"],
                                        match_model=candidate.get("match_model"), match_brand=candidate.get("match_brand"))
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


class ScanOptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["missing", "review"] = "missing"
    language: Literal["zh-CN", "en"] = "zh-CN"


def _snapshot(job: EnrichmentJob) -> dict:
    with job.lock:
        options = job.items[0] if job.items else {}
        counts = Counter(item["status"] for item in job.items)
        return {"id": job.id, "state": job.state, "total": len(job.items), "processed": job.processed,
                "ready": counts["ready"], "unchanged": counts["unchanged"],
                "skipped": counts["skipped"], "failed": counts["failed"],
                "mode": options.get("_mode", "missing"), "language": options.get("_language", "zh-CN"),
                "reasons": dict(Counter(item["reason"] for item in job.items if item.get("reason")))}


@router.post("/jobs", status_code=202)
def start(request: Request, draft: ScanOptions = ScanOptions(), account: Account = Depends(require_token), settings: Settings = Depends(get_settings), connection: sqlite3.Connection = Depends(get_db)) -> dict:
    try:
        items = [dict(item, _mode=draft.mode, _language=draft.language) for item in inventory_items(connection)]
        return _snapshot(_manager(request).start(account, settings, items, preview=True))
    except ValueError as error:
        raise HTTPException(409, str(error)) from error


@router.get("/jobs/active")
def active(request: Request, account: Account = Depends(require_token)) -> dict | None:
    job = _manager(request).active(account, preview=True)
    return _snapshot(job) if job else None


@router.get("/jobs/{job_id}")
def status(job_id: str, request: Request, account: Account = Depends(require_token)) -> dict:
    return _snapshot(_job(request, account, job_id))


@router.get("/jobs/{job_id}/rows")
def rows(job_id: str, request: Request, page: int = Query(1, ge=1),
         status: Literal["ready", "unchanged", "skipped", "failed", "pending"] | None = None,
         account: Account = Depends(require_token)) -> dict:
    job = _job(request, account, job_id)
    with job.lock:
        matching = [item for item in job.items if status is None or item["status"] == status]
        selected = matching[(page - 1) * 50:page * 50]
        return {"page": page, "total": len(matching), "page_size": 50, "items": [
            {key: item.get(key) for key in ("id", "sku", "name", "updated_at", "status", "reason", "changes",
                                          "match_source", "match_model", "match_brand", "source_url")}
            for item in selected]}


@router.post("/jobs/{job_id}/cancel")
def cancel(job_id: str, request: Request, account: Account = Depends(require_token)) -> dict:
    job = _job(request, account, job_id)
    job.cancel.set()
    return _snapshot(job)


@router.post("/jobs/{job_id}/retry", status_code=202)
def retry(job_id: str, request: Request, account: Account = Depends(require_token), settings: Settings = Depends(get_settings), connection: sqlite3.Connection = Depends(get_db)) -> dict:
    job = _job(request, account, job_id)
    if job.state not in {"completed", "cancelled"}:
        raise HTTPException(409, "Wait for analysis to stop.")
    current = {item["id"]: item for item in inventory_items(connection)}
    if not any(item["status"] in {"failed", "pending"} and item["id"] in current for item in job.items):
        raise HTTPException(409, "No failed or pending rows to retry.")
    items = [dict(current[item["id"]], _mode=item.get("_mode", "missing"), _language=item.get("_language", "zh-CN"))
             if item["status"] in {"failed", "pending"} else item.copy()
             for item in job.items if item["id"] in current]
    try:
        return _snapshot(_manager(request).start(account, settings, items, preview=True))
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
    parameter_keys: list[str] | None = Field(default=None, min_length=1, max_length=500)
    confirm_match: bool = False


class ApplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[Selection] = Field(min_length=1, max_length=50)


def apply_one(connection: sqlite3.Connection, settings: Settings, row: dict, selection: Selection) -> str:
    selected = {key: row["changes"][key]["after"] for key in set(selection.fields) if key in row["changes"]}
    if not selected or len(selected) != len(set(selection.fields)):
        raise ValueError("Selected field is not in the preview")
    if row.get("match_source") == "mpn" and not selection.confirm_match:
        raise ValueError("Confirm the manufacturer model match before applying.")
    if selection.parameter_keys is not None:
        proposed = selected.get("parameters", {})
        keys = set(selection.parameter_keys)
        if len(keys) != len(selection.parameter_keys) or not keys.issubset(proposed):
            raise ValueError("Selected parameter is not in the preview")
        selected["parameters"] = {key: proposed[key] for key in selection.parameter_keys}
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
        component = ComponentPayload(inventory_unit_cost=current["inventory_unit_cost"], id=current["id"], sku=current["sku"], name=current["name"], category=category,
            package_name=package, description=description, location=current["location"], quantity=current["quantity"],
            min_stock=current["min_stock"], updated_at=now, allocations=load_allocations(connection, current["id"]) if managed else None,
            base_updated_at=datetime.fromisoformat(row["updated_at"].replace("Z", "+00:00")) if managed else None)
        _save_payload(connection, settings, PushRequest(device_id="admin-web", cost_protocol=1, inventory_protocol=1 if managed else 0, components=[component]))
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
