"""Read-only order previews and explicitly selected account-scoped metadata edits."""
from __future__ import annotations

from datetime import datetime
import re
import sqlite3
import unicodedata

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from pydantic import BaseModel, ConfigDict, Field, field_validator
import xlrd

from ..auth import require_token
from ..config import Settings, get_settings
from ..database import get_db
from ..schemas import ComponentPayload, PushRequest
from ..storage import load_allocations
from .operations import (
    AdminOperationConflict, AdminOperationNotFound, _canonical_payload,
    _component_row, _now_datetime_after, _require_version, _retry_target,
    _save_payload, _save_receipt, _storage_time,
)

router = APIRouter(prefix="/components/order-metadata", dependencies=[Depends(require_token)])
MAX_BYTES = 16 * 1024 * 1024
SKU = re.compile(r"C[0-9]{1,10}\Z")
CHINESE = re.compile(r"[\u3400-\u9fff]")
PARAMETER_LINE = re.compile(r"^\s*参数[·：:]([^：:\r\n]+)[：:]([^\r\n]*)$")
PARAMETER_ALIASES = {
    "阻值": ("阻值", "电阻值", "resistance"),
    "容量": ("容量", "容值", "电容量", "capacitance"),
    "电感量": ("电感量", "电感值", "感值", "inductance"),
    "耐压": ("耐压", "额定电压", "工作电压", "电压", "voltage rating", "rated voltage", "voltage"),
    "精度": ("精度", "容差", "误差", "阻值精度", "tolerance"),
    "功率": ("功率", "额定功率", "power(watts)", "power rating", "rated power", "power"),
    "额定电流": ("额定电流", "rated current", "current rating"),
    "频率": ("频率", "frequency"),
}


def _parameter_identity(label: str, value: str) -> tuple[str, str]:
    normalized = " ".join(unicodedata.normalize("NFKC", label).lower().split())
    unit_match = re.fullmatch(r"(.+?)\s*\(([^()]+)\)", normalized)
    base = unit_match[1].strip() if unit_match else normalized
    canonical = next((key for key, aliases in PARAMETER_ALIASES.items() if normalized in aliases or base in aliases), label.strip())
    value = value.strip()
    if unit_match and re.fullmatch(r"[+-]?\d+(?:\.\d+)?", value):
        unit = unicodedata.normalize("NFKC", label).split("(")[-1].removesuffix(")").strip()
        if re.fullmatch(r"[pnumkMµμ]?(?:V|A|W|F|H|Ω)|ohm|%", unit, re.IGNORECASE):
            value += unit
    return canonical, value
# Only unambiguous electrical fields, never model numbers or free-form copy.
PARAMETER_PATTERNS = {
    "阻值": r"\d+(?:\.\d+)?\s*(?:[kKmM]?Ω|[kKmM]?ohm)",
    "容量": r"\d+(?:\.\d+)?\s*[pnumµμ]?F",
    "耐压": r"\d+(?:\.\d+)?\s*(?:mV|V)",
    "电感量": r"\d+(?:\.\d+)?\s*[numµμ]?H",
    "功率": r"(?:\d+(?:\.\d+)?|\d+/\d+)\s*(?:mW|W)",
    "精度": r"(?:±|\+/-)?\s*\d+(?:\.\d+)?\s*%",
    "额定电流": r"\d+(?:\.\d+)?\s*(?:µA|μA|uA|mA|A)",
    "频率": r"\d+(?:\.\d+)?\s*(?:Hz|kHz|MHz|GHz)",
}


class Changes(BaseModel):
    model_config = ConfigDict(extra="forbid")
    category: str | None = Field(default=None, min_length=1, max_length=120)
    package_name: str | None = Field(default=None, min_length=1, max_length=120)
    parameters: dict[str, str] | None = None

    @field_validator("category", "package_name")
    @classmethod
    def nonblank(cls, value: str | None) -> str | None:
        if value is None or not value.strip() or "\n" in value or "\r" in value:
            raise ValueError("Selected fields must contain a non-empty single-line value.")
        return value.strip()

    @field_validator("category")
    @classmethod
    def chinese_category(cls, value: str | None) -> str | None:
        if value is not None and not CHINESE.search(value):
            raise ValueError("Category must use an explicit Chinese source.")
        return value

    @field_validator("parameters")
    @classmethod
    def electrical_parameters(cls, value: dict[str, str] | None) -> dict[str, str]:
        if value is None or not value or len(value) > len(PARAMETER_PATTERNS):
            raise ValueError("Select at least one explicit electrical parameter.")
        for key, item in value.items():
            pattern = PARAMETER_PATTERNS.get(key)
            if pattern is None or "\n" in item or "\r" in item or re.fullmatch(pattern, item.strip()) is None:
                raise ValueError("Unsupported or ambiguous electrical parameter.")
        return {key: item.strip() for key, item in value.items()}


class ApplyItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    component_id: str = Field(min_length=1, max_length=200)
    sku: str = Field(pattern=r"^C[0-9]{1,10}$")
    expected_updated_at: datetime
    request_id: str = Field(min_length=8, max_length=120)
    changes: Changes


class ApplyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    items: list[ApplyItem] = Field(min_length=1, max_length=500)


def parameters_from_description(description: str | None) -> dict[str, str]:
    result = {}
    for line in (description or "").splitlines():
        match = PARAMETER_LINE.fullmatch(line)
        if match:
            key, value = _parameter_identity(match[1], match[2])
            result[key] = value
    return result


def merge_parameters(description: str | None, parameters: dict[str, str]) -> str:
    """Preserve every unselected line and its exact existing line ending."""
    original = description or ""
    lines = original.splitlines(keepends=True)
    found = set()
    for index, line in enumerate(lines):
        body = line.rstrip("\r\n")
        match = PARAMETER_LINE.fullmatch(body)
        key = _parameter_identity(match[1], match[2])[0] if match else None
        if key in parameters:
            lines[index] = f"参数·{key}：{parameters[key]}" + line[len(body):]
            found.add(key)
    merged = "".join(lines)
    ending = "\r\n" if "\r\n" in original else "\n"
    for key, value in parameters.items():
        if key not in found:
            if merged and not merged.endswith(("\r", "\n")):
                merged += ending
            merged += f"参数·{key}：{value}" + ending
    return merged


def _header(value: str) -> str:
    return re.sub(r"\s+", "", value).replace("（", "(").replace("）", ")")


def _parameters(text: str, category: str, columns: dict[str, str]) -> dict[str, str]:
    result = {}
    for key, pattern in PARAMETER_PATTERNS.items():
        explicit = columns.get(key, "")
        if explicit and re.fullmatch(pattern, explicit):
            result[key] = explicit
            continue
        # Unit-bearing copy is only interpreted in a relevant component family.
        families = {"阻值": "电阻", "功率": "电阻", "容量": "电容", "耐压": "电容", "电感量": "电感"}
        if key not in families or families[key] not in category:
            continue
        matches = set(match.group().strip() for match in re.finditer(
            r"(?<![\w./])" + pattern + r"(?![\w])", text
        ))
        if len(matches) == 1:
            result[key] = matches.pop()
    # Precision requires an explicit ± marker when read from prose.
    matches = set(re.findall(r"(?:±|\+/-)\s*\d+(?:\.\d+)?\s*%", text))
    if "精度" not in result and len(matches) == 1:
        result["精度"] = matches.pop()
    return result


def parse_order(content: bytes) -> dict[str, dict]:
    if not content.startswith(bytes.fromhex("D0CF11E0A1B11AE1")):
        raise ValueError("请选择立创商城导出的 BIFF .xls 订单表。")
    try:
        book = xlrd.open_workbook(file_contents=content, on_demand=True)
    except (xlrd.XLRDError, xlrd.compdoc.CompDocError, ValueError, EOFError) as error:
        raise ValueError("无法读取订单 .xls 文件。") from error
    grouped: dict[str, dict] = {}
    recognized = False
    scanned = 0
    try:
        if book.nsheets > 64:
            raise ValueError("工作表超过 64 个，请拆分文件。")
        for sheet in book.sheets():
            if sheet.ncols > 256 or scanned + sheet.nrows > 100_000:
                raise ValueError("订单表扫描范围过大，请拆分文件。")
            scanned += sheet.nrows
            headers = None
            for row_index in range(sheet.nrows):
                cells = [str(cell).strip() for cell in sheet.row_values(row_index)]
                normalized = [_header(cell) for cell in cells]
                if "商品编号" in normalized and "商品名称" in normalized and any(
                    key in normalized for key in ("订购数量", "订购数量(修改后)")
                ):
                    headers = normalized
                    recognized = True
                    continue
                if headers is None:
                    if row_index >= 39:
                        break
                    continue
                columns = dict(zip(headers, cells))
                sku = columns.get("商品编号", "").upper()
                if not SKU.fullmatch(sku):
                    continue
                category = next((columns[key] for key in ("商品类型", "分类", "商品分类") if columns.get(key)), "")
                package = next((columns[key] for key in ("封装格式", "封装") if columns.get(key)), "")
                name = columns.get("商品名称", "")
                text = " ".join([name, *(columns.get(key, "") for key in ("参数", "商品参数", "规格参数"))])
                candidate = {
                    "category": category if CHINESE.search(category) else "",
                    "package_name": package,
                    "parameters": _parameters(text, category, columns),
                }
                if len(candidate["category"]) > 120 or len(package) > 120:
                    raise ValueError("分类或封装字段超过 120 字符。")
                if sku in grouped:
                    prior = grouped[sku]
                    prior["source_rows"].append(row_index + 1)
                    if prior["candidate"] != candidate or prior["name"] != name:
                        prior["conflict"] = True
                else:
                    if len(grouped) >= 500:
                        raise ValueError("订单表超过 500 个不同料号，请分批导入。")
                    grouped[sku] = {"candidate": candidate, "name": name, "source_rows": [row_index + 1], "conflict": False}
    finally:
        book.release_resources()
    if not recognized or not grouped:
        raise ValueError("未识别立创订单表头或有效 C 料号明细。")
    return grouped


@router.post("/preview")
def preview(
    file: UploadFile = File(...),
    connection: sqlite3.Connection = Depends(get_db),
) -> dict:
    try:
        content = file.file.read(MAX_BYTES + 1)
        if len(content) > MAX_BYTES:
            raise HTTPException(413, "订单表超过 16 MiB，请分批导入。")
        grouped = parse_order(content)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    finally:
        file.file.close()
    rows = []
    summary = dict.fromkeys(("ready", "unchanged", "unmatched", "conflict"), 0)
    for sku, source in grouped.items():
        component = connection.execute("SELECT * FROM components WHERE sku = ? AND deleted = 0", (sku,)).fetchone()
        changes = {}
        messages = []
        state = "unmatched"
        if source["conflict"]:
            state = "conflict"
            messages.append("同料号多行商品资料不一致，请核对源文件。")
        elif component is None:
            messages.append("当前账户没有此料号；不会新建元件。")
        else:
            for field in ("category", "package_name"):
                after = source["candidate"][field]
                if after and after != component[field]:
                    changes[field] = {"before": component[field], "after": after}
            before = parameters_from_description(component["description"])
            after = {key: value for key, value in source["candidate"]["parameters"].items() if before.get(key) != value}
            if after:
                changes["parameters"] = {"before": {key: before.get(key, "") for key in after}, "after": after}
            state = "ready" if changes else "unchanged"
        rows.append({
            "component_id": component["id"] if component else None,
            "sku": sku, "name": component["name"] if component else source["name"],
            "expected_updated_at": component["updated_at"] if component else None,
            "status": state, "messages": messages, "source_rows": source["source_rows"], "changes": changes,
        })
        summary[state] += 1
    return {"filename": file.filename or "order.xls", "rows": rows, "summary": summary}


def apply_item(connection: sqlite3.Connection, settings: Settings, item: ApplyItem) -> str:
    payload = _canonical_payload(item)
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        if _retry_target(connection, item.request_id, "order_metadata", item.component_id, payload):
            return "unchanged"
        row = _component_row(connection, item.component_id)
        if row["sku"] != item.sku:
            raise AdminOperationConflict("元件料号已变更，请重新预览。")
        _require_version(row, item.expected_updated_at)
        description = row["description"]
        if item.changes.parameters:
            description = merge_parameters(description, item.changes.parameters)
        category = item.changes.category or row["category"]
        package = item.changes.package_name or row["package_name"]
        changed = (category, package, description) != (row["category"], row["package_name"], row["description"])
        now = _now_datetime_after(row["updated_at"])
        if changed:
            managed = bool(row["inventory_managed"])
            component = ComponentPayload(
                id=row["id"], sku=row["sku"], name=row["name"], category=category,
                package_name=package, description=description, location=row["location"],
                quantity=row["quantity"], min_stock=row["min_stock"], updated_at=now,
                allocations=load_allocations(connection, row["id"]) if managed else None,
                base_updated_at=item.expected_updated_at if managed else None,
            )
            _save_payload(connection, settings, PushRequest(
                device_id="admin-web", inventory_protocol=1 if managed else 0, components=[component],
            ))
        _save_receipt(connection, item.request_id, "order_metadata", item.component_id, payload, _storage_time(now))
        return "updated" if changed else "unchanged"


@router.post("/apply")
def apply(
    draft: ApplyRequest,
    settings: Settings = Depends(get_settings),
    connection: sqlite3.Connection = Depends(get_db),
) -> dict:
    if not settings.web_inventory_enabled:
        raise HTTPException(403, "Web inventory writes are disabled.")
    items = []
    summary = dict.fromkeys(("updated", "unchanged", "conflict", "failed"), 0)
    counts = {}
    for item in draft.items:
        counts[item.sku] = counts.get(item.sku, 0) + 1
    for item in draft.items:
        message = ""
        try:
            if counts[item.sku] > 1:
                raise AdminOperationConflict("同批次料号重复，请重新预览。")
            state = apply_item(connection, settings, item)
        except AdminOperationConflict as error:
            state, message = "conflict", str(error)
        except AdminOperationNotFound:
            state, message = "failed", "当前账户中未找到元件。"
        except (sqlite3.IntegrityError, ValueError):
            state, message = "failed", "元数据更新失败，请重新预览。"
        items.append({"component_id": item.component_id, "sku": item.sku, "status": state, "message": message})
        summary[state] += 1
    return {"items": items, "summary": summary}
