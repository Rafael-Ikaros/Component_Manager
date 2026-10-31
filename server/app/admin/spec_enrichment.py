"""Temporary, account-scoped catalog enrichment for existing inventory rows."""

from __future__ import annotations

from dataclasses import dataclass, field
from dataclasses import replace
import re
import sqlite3
from threading import Event, Lock, Thread
from uuid import uuid4

from ..accounts import Account
from ..config import Settings
from ..database import _connect
from ..part_lookup import lookup_explicit_sku_metadata
from ..repositories import save_sync_payload_in_transaction
from ..schemas import ComponentPayload, PushRequest
from ..storage import load_allocations
from .operations import _now_datetime_after


SKU_PATTERN = re.compile(r"C\d+", re.IGNORECASE)
PARAMETER_LINE = re.compile(r"(?m)^\s*参数[·：:]", re.IGNORECASE)
TOKEN_LEFT = r"(?<![\w])"
TOKEN_RIGHT = r"(?![\w])"


def _explicit_description_parameters(
    description: str | None, category: str,
) -> dict[str, str]:
    """Extract only standalone, unit-bearing values stated in catalog copy."""
    if not description:
        return {}
    fields: list[tuple[str, str]] = []
    category_key = category.casefold()
    if "电阻" in category_key or "resistor" in category_key:
        fields.extend([
            ("阻值", r"\d{1,6}(?:\.\d{1,6})?\s*(?:[kKmM]?Ω|[kKmM]?ohm)"),
            ("功率", r"\d{1,6}(?:\.\d{1,6})?\s*(?:mW|W)"),
        ])
    elif "电容" in category_key or "capacitor" in category_key:
        fields.extend([
            ("容量", r"\d{1,6}(?:\.\d{1,6})?\s*[pnumµμ]?F"),
            ("耐压", r"\d{1,6}(?:\.\d{1,6})?\s*(?:mV|V)"),
        ])
    elif "电感" in category_key or "inductor" in category_key:
        fields.append(("电感量", r"\d{1,6}(?:\.\d{1,6})?\s*[numµμ]?H"))
    if not fields:
        return {}
    fields.append(("精度", r"(?:±|\+/-)\s*\d{1,3}(?:\.\d{1,3})?\s*%"))
    result: dict[str, str] = {}
    for label, pattern in fields:
        match = re.search(TOKEN_LEFT + pattern + TOKEN_RIGHT, description, re.IGNORECASE)
        if match:
            result[label] = match.group().strip()
    return result


def _candidate(row: sqlite3.Row) -> bool:
    return bool(
        SKU_PATTERN.fullmatch(str(row["sku"]).strip())
        and not PARAMETER_LINE.search(str(row["description"] or ""))
    )


def candidates(
    connection: sqlite3.Connection, *, limit: int | None = None,
) -> tuple[int, list[dict[str, str]]]:
    rows = connection.execute(
        "SELECT id, sku, name, description FROM components "
        "WHERE deleted = 0 ORDER BY sku, id"
    )
    items: list[dict[str, str]] = []
    total = 0
    for row in rows:
        if not _candidate(row):
            continue
        total += 1
        if limit is None or len(items) < limit:
            items.append({
                "id": str(row["id"]), "sku": str(row["sku"]),
                "name": str(row["name"]),
            })
    return total, items


def _merged_description(
    description: str | None, official_description: str | None,
    parameters: dict[str, str],
) -> str:
    original = description or ""
    additions: list[str] = []
    if official_description and "官方描述：" not in original:
        additions.append(f"官方描述：{official_description.strip()}")
    known_keys = {
        line.split("：", 1)[0].removeprefix("参数·").removeprefix("参数：").strip().casefold()
        for line in original.splitlines() if PARAMETER_LINE.match(line)
    }
    for key, value in parameters.items():
        key, value = key.strip(), value.strip()
        if key and value and key.casefold() not in known_keys:
            additions.append(f"参数·{key}：{value}")
            known_keys.add(key.casefold())
    return "\n".join(part for part in [original.rstrip(), *additions] if part)


@dataclass
class EnrichmentJob:
    id: str
    account_id: str
    database_path: str
    items: list[dict[str, str]]
    state: str = "queued"
    processed: int = 0
    updated: int = 0
    skipped: int = 0
    failed: int = 0
    cancel: Event = field(default_factory=Event)
    lock: Lock = field(default_factory=Lock)
    thread: Thread | None = None
    preview: bool = False

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            if self.preview:
                return {"id": self.id, "mode": "catalog_preview", "state": self.state,
                        "total": len(self.items), "processed": self.processed,
                        "ready": sum(item["status"] == "ready" for item in self.items),
                        "skipped": self.skipped, "failed": self.failed}
            details = [item.copy() for item in self.items if item["status"] == "failed"]
            details.extend(item.copy() for item in self.items if item["status"] == "skipped")
            return {
                "id": self.id, "state": self.state, "total": len(self.items),
                "processed": self.processed, "updated": self.updated,
                "skipped": self.skipped, "failed": self.failed,
                "items": details[:50],
                "omitted_items": max(0, self.failed + self.skipped - 50),
                "mode": "catalog_preview" if self.preview else "spec_enrichment",
            }

    def mark(self, index: int, status: str, reason: str = "") -> None:
        with self.lock:
            self.items[index]["status"] = status
            self.items[index]["reason"] = reason
            self.processed += 1
            if status == "updated":
                self.updated += 1
            elif status == "skipped":
                self.skipped += 1
            else:
                self.failed += 1


class EnrichmentJobs:
    def __init__(self) -> None:
        self._jobs: dict[str, EnrichmentJob] = {}
        self._lock = Lock()
        self._blocked_accounts: set[tuple[str, str]] = set()

    def cancel_all(self) -> None:
        with self._lock:
            for job in self._jobs.values():
                job.cancel.set()

    def cancel_account_and_wait(self, account_id: str, database_path: str) -> None:
        with self._lock:
            self._blocked_accounts.add((account_id, database_path))
            threads = []
            for job in self._jobs.values():
                if job.account_id == account_id and job.database_path == database_path:
                    job.cancel.set()
                    if job.thread is not None:
                        threads.append(job.thread)
        for thread in threads:
            thread.join()

    def get(self, job_id: str, account: Account) -> EnrichmentJob | None:
        with self._lock:
            job = self._jobs.get(job_id)
            if job and job.account_id == account.account_id and job.database_path == account.database_path:
                return job
            return None

    def active(self, account: Account, *, preview: bool = False) -> EnrichmentJob | None:
        with self._lock:
            return next((job for job in self._jobs.values()
                         if job.account_id == account.account_id
                         and job.database_path == account.database_path
                         and job.preview == preview
                         and job.state in {"queued", "running"}), None)

    def clear(self, job_id: str, account: Account) -> bool:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.account_id != account.account_id or job.database_path != account.database_path:
                return False
            if job.state in {"queued", "running"}:
                raise ValueError("Cannot clear an active enrichment job.")
            del self._jobs[job_id]
            return True

    def _prune_finished(self, finished: EnrichmentJob) -> None:
        """Retain only the most recent finished job for this account."""
        with self._lock:
            for job_id, job in list(self._jobs.items()):
                if (job is not finished
                    and job.account_id == finished.account_id
                    and job.database_path == finished.database_path
                    and job.preview == finished.preview
                    and job.state in {"completed", "cancelled"}):
                    del self._jobs[job_id]

    def start(
        self, account: Account, settings: Settings,
        items: list[dict[str, str]],
        *, preview: bool = False,
    ) -> EnrichmentJob:
        job = EnrichmentJob(
            id=str(uuid4()), account_id=account.account_id,
            database_path=account.database_path,
            items=[{**item, "status": item.get("status", "pending") if preview and item.get("status") in {"ready", "unchanged", "skipped"} else "pending",
                    "reason": item.get("reason", "") if preview else ""} for item in items],
            preview=preview,
        )
        with self._lock:
            if (account.account_id, account.database_path) in self._blocked_accounts:
                raise ValueError("This account is being deleted.")
            if any(
                existing.account_id == account.account_id
                and existing.database_path == account.database_path
                and existing.state in {"queued", "running"}
                for existing in self._jobs.values()
            ):
                raise ValueError("An enrichment job is already running for this account.")
            self._jobs[job.id] = job
        job.thread = Thread(target=self._run, args=(job, account, settings), daemon=True)
        job.thread.start()
        return job

    def _run(self, job: EnrichmentJob, account: Account, settings: Settings) -> None:
        with job.lock:
            job.state = "running"
        account_settings = settings if account.role == "admin" else replace(settings, mqtt_enabled=False)
        try:
            if job.preview:
                from .catalog_metadata import scan_preview
                scan_preview(job)
                return
            connection = _connect(job.database_path, existing_only=account.role == "user")
            try:
                for index, item in enumerate(job.items):
                    if job.cancel.is_set():
                        break
                    try:
                        status, reason = _enrich_one(connection, account_settings, item)
                    except Exception as error:
                        status, reason = "failed", f"{type(error).__name__}: {error}"
                    job.mark(index, status, reason)
            finally:
                connection.close()
        except Exception as error:
            for index, item in enumerate(job.items):
                if item["status"] == "pending":
                    job.mark(index, "failed", f"Database unavailable: {type(error).__name__}")
        finally:
            with job.lock:
                job.state = "cancelled" if job.cancel.is_set() else "completed"
            self._prune_finished(job)


def _enrich_one(
    connection: sqlite3.Connection, settings: Settings, item: dict[str, str],
) -> tuple[str, str]:
    row = connection.execute(
        "SELECT * FROM components WHERE id = ?", (item["id"],)
    ).fetchone()
    if row is None or row["deleted"] or not _candidate(row):
        return "skipped", "Component is deleted or already has parameter notes."
    sku, version = str(row["sku"]), str(row["updated_at"])
    result = lookup_explicit_sku_metadata(settings=settings, sku=sku)
    if not result.found or not result.sku or result.sku.upper() != sku.upper():
        return "skipped", "No exact catalog match for this LCSC SKU."
    parameters = dict(result.parameters)
    if not parameters:
        category = str(row["category"] or "")
        if not any(term in category.casefold() for term in (
            "电阻", "resistor", "电容", "capacitor", "电感", "inductor",
        )):
            category = result.category or result.category_path or category
        parameters = _explicit_description_parameters(
            result.description, category,
        )
    if not parameters:
        return "skipped", "Catalog did not provide explicit parameters."
    description = _merged_description(row["description"], result.description, parameters)
    if description == str(row["description"] or ""):
        return "skipped", "No missing catalog notes."

    with connection:
        connection.execute("BEGIN IMMEDIATE")
        current = connection.execute(
            "SELECT * FROM components WHERE id = ?", (item["id"],)
        ).fetchone()
        if (current is None or current["deleted"] or current["updated_at"] != version
                or current["sku"] != sku):
            return "failed", "Component changed during catalog lookup; retry after review."
        managed = bool(current["inventory_managed"])
        payload = ComponentPayload(
            id=str(current["id"]), sku=sku, name=str(current["name"]),
            category=str(current["category"]), package_name=str(current["package_name"]),
            location=str(current["location"]), description=description,
            quantity=int(current["quantity"]), min_stock=int(current["min_stock"]),
            updated_at=_now_datetime_after(version), deleted=False,
            allocations=load_allocations(connection, item["id"]) if managed else None,
            base_updated_at=version if managed else None,
        )
        save_sync_payload_in_transaction(
            connection,
            PushRequest(
                device_id="admin-web", inventory_protocol=1 if managed else 0,
                components=[payload],
            ),
            mqtt_topic_prefix=settings.mqtt_topic_prefix if settings.mqtt_enabled else None,
        )
    return "updated", ""
