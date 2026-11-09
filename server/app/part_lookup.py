from __future__ import annotations

from dataclasses import dataclass
from html import unescape
import json
from pathlib import Path
import re
from threading import Lock
import time
from typing import Any
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

from .config import Settings
from .schemas import LcscLookupResponse, PartLookupResponse, RecognitionRulesMetaResponse


class LookupRequestError(RuntimeError):
    pass


class RecognitionRulesRefreshError(RuntimeError):
    pass


_PUBLIC_WEB_CACHE: dict[str, tuple[float, LcscLookupResponse]] = {}
_PUBLIC_WEB_CACHE_LOCK = Lock()
_SCRIPT_BLOCK_PATTERN = re.compile(
    r"<script[^>]*>(?P<body>.*?)</script>",
    re.IGNORECASE | re.DOTALL,
)
_DETAIL_LINK_PATTERN = re.compile(
    r"/product-detail/(?P<sku>C\d+)\.html",
    re.IGNORECASE,
)
_META_TAG_PATTERN = re.compile(
    r"""<meta[^>]+(?:property|name)=["'](?P<key>[^"']+)["'][^>]+content=["'](?P<value>.*?)["'][^>]*>""",
    re.IGNORECASE | re.DOTALL,
)
_TITLE_PATTERN = re.compile(
    r"<title>(?P<value>.*?)</title>",
    re.IGNORECASE | re.DOTALL,
)
_SKU_KEYS = (
    "product_number",
    "productNumber",
    "lcscPartNumber",
    "sku",
    "productID",
)
_MPN_KEYS = (
    "product_model",
    "productModel",
    "mfrPartNumber",
    "mpn",
    "partNumber",
    "model",
)
_NAME_KEYS = (
    "productIntroEn",
    "productIntro",
    "productDescEn",
    "productDesc",
    "productName",
    "name",
    "title",
)
_PACKAGE_KEYS = (
    "encapStandard",
    "encap_standard",
    "packageName",
    "package",
    "pkg",
)
_CATEGORY_KEYS = (
    "catalogName",
    "catalog_name",
    "categoryName",
    "category",
)
_BRAND_KEYS = (
    "brandName",
    "brand_name",
    "manufacturerName",
    "brand",
)


@dataclass(frozen=True)
class RecognitionValues:
    combined: str
    combined_lower: str
    sku: str | None
    mpn: str | None
    name: str | None
    brand: str | None
    package_hint: str | None
    source_type: str | None


@dataclass(frozen=True)
class PackagePattern:
    normalized: str
    compiled: re.Pattern[str]


@dataclass(frozen=True)
class ModelPattern:
    id: str
    match_type: str
    pattern: str
    vendor: str | None
    model_family: str | None
    package_name: str | None
    category: str | None
    confidence: str | None
    priority: int
    compiled: re.Pattern[str] | None

    def matches(self, values: RecognitionValues) -> bool:
        lowered_pattern = self.pattern.casefold()
        if self.match_type == "contains":
            return lowered_pattern in values.combined_lower
        if self.match_type == "prefix":
            return values.combined_lower.startswith(lowered_pattern) or (
                f" {lowered_pattern}" in values.combined_lower
            )
        return self.compiled is not None and bool(self.compiled.search(values.combined))


@dataclass(frozen=True)
class KeywordRule:
    id: str
    keywords: tuple[str, ...]
    vendor: str | None
    model_family: str | None
    category: str | None
    confidence: str | None
    priority: int

    def matches(self, values: RecognitionValues) -> bool:
        return any(keyword.casefold() in values.combined_lower for keyword in self.keywords)


@dataclass(frozen=True)
class RecognitionRuleSet:
    version: str
    updated_at: str | None
    source: str
    active_path: Path
    override_path: Path
    package_patterns: tuple[PackagePattern, ...]
    model_patterns: tuple[ModelPattern, ...]
    keyword_rules: tuple[KeywordRule, ...]


def lookup_part_metadata(
    settings: Settings,
    sku: str | None = None,
    mpn: str | None = None,
    name: str | None = None,
    brand: str | None = None,
    package_hint: str | None = None,
    source_type: str | None = None,
) -> PartLookupResponse:
    rules = _load_rules(settings)
    values = _build_values(
        sku=sku,
        mpn=mpn,
        name=name,
        brand=brand,
        package_hint=package_hint,
        source_type=source_type,
    )
    local_result = _lookup_local_rules(rules=rules, values=values)

    if settings.enable_web_fallback_resolvers and any((values.sku, values.mpn, values.name)):
        try:
            web_result = lookup_public_lcsc_product(
                settings=settings,
                sku=values.sku,
                mpn=values.mpn,
                name=values.name,
            )
        except LookupRequestError:
            web_result = None
        if web_result is not None and web_result.found:
            return _merge_lcsc_and_local(
                values=values,
                lcsc_result=web_result,
                local_result=local_result,
            )

    if local_result is not None:
        return local_result

    return PartLookupResponse(
        found=False,
        source="none",
        confidence="none",
        rule_version=rules.version,
    )


def lookup_explicit_sku_metadata(
    settings: Settings, sku: str,
) -> LcscLookupResponse:
    """Resolve a user-requested exact LCSC SKU without the automatic fallback switch.

    The automatic part lookup intentionally tolerates unavailable sources. This
    explicit enrichment path reports an actual public network failure so the job
    can retry the row instead of silently treating it as absent catalog data.
    """
    normalized = _normalize_text(sku)
    if normalized is None or _build_lcsc_product_url(normalized) is None:
        raise ValueError("A valid LCSC SKU is required.")
    return lookup_public_lcsc_product(settings, sku=normalized)


def get_recognition_rules_meta(
    settings: Settings,
    refreshed: bool = False,
) -> RecognitionRulesMetaResponse:
    rules = _load_rules(settings)
    return RecognitionRulesMetaResponse(
        version=rules.version,
        updated_at=rules.updated_at,
        source=rules.source,
        active_path=str(rules.active_path),
        override_path=str(rules.override_path),
        remote_url=settings.import_rules_remote_url or None,
        web_fallback_enabled=settings.enable_web_fallback_resolvers,
        refreshed=refreshed,
    )


def refresh_recognition_rules(settings: Settings) -> RecognitionRulesMetaResponse:
    remote_url = settings.import_rules_remote_url.strip()
    if not remote_url:
        return get_recognition_rules_meta(settings, refreshed=False)

    override_path = _override_rules_path(settings)
    override_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with urlopen(remote_url, timeout=12) as response:
            payload = response.read().decode("utf-8")
    except Exception as error:  # pragma: no cover - network failures depend on host
        raise RecognitionRulesRefreshError(
            f"Recognition rules refresh failed: {error}",
        ) from error

    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as error:
        raise RecognitionRulesRefreshError(
            "Recognition rules refresh returned invalid JSON.",
        ) from error

    _build_rule_set(
        parsed,
        source="remote_cache",
        active_path=override_path,
        override_path=override_path,
    )
    override_path.write_text(
        json.dumps(parsed, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
    )
    return get_recognition_rules_meta(settings, refreshed=True)


def _build_values(
    sku: str | None,
    mpn: str | None,
    name: str | None,
    brand: str | None,
    package_hint: str | None,
    source_type: str | None,
) -> RecognitionValues:
    normalized_sku = _normalize_text(sku)
    normalized_mpn = _normalize_text(mpn)
    normalized_name = _normalize_text(name)
    normalized_brand = _normalize_text(brand)
    normalized_package_hint = _normalize_text(package_hint)
    normalized_source_type = _normalize_text(source_type)
    combined_parts = [
        value
        for value in (
            normalized_sku,
            normalized_mpn,
            normalized_name,
            normalized_brand,
            normalized_package_hint,
            normalized_source_type,
        )
        if value
    ]
    combined = " ".join(combined_parts)
    return RecognitionValues(
        combined=combined,
        combined_lower=combined.casefold(),
        sku=normalized_sku,
        mpn=normalized_mpn,
        name=normalized_name,
        brand=normalized_brand,
        package_hint=normalized_package_hint,
        source_type=normalized_source_type,
    )


def _lookup_local_rules(
    rules: RecognitionRuleSet,
    values: RecognitionValues,
) -> PartLookupResponse | None:
    normalized_package = _detect_package(rules, values)
    model_rule = next(
        (rule for rule in sorted(rules.model_patterns, key=lambda item: item.priority, reverse=True) if rule.matches(values)),
        None,
    )
    keyword_rule = next(
        (
            rule
            for rule in sorted(rules.keyword_rules, key=lambda item: item.priority, reverse=True)
            if (model_rule is None or rule.priority >= model_rule.priority) and rule.matches(values)
        ),
        None,
    )

    recognized_category = (
        model_rule.category
        if model_rule and model_rule.category
        else keyword_rule.category
        if keyword_rule and keyword_rule.category
        else None
    )
    recognized_package = (
        model_rule.package_name
        if model_rule and model_rule.package_name
        else normalized_package or values.package_hint
    )
    recognized_vendor = (
        values.brand
        or (model_rule.vendor if model_rule else None)
        or (keyword_rule.vendor if keyword_rule else None)
    )
    recognized_model_family = (
        (model_rule.model_family if model_rule else None)
        or (keyword_rule.model_family if keyword_rule else None)
    )
    confidence = (
        (model_rule.confidence if model_rule else None)
        or (keyword_rule.confidence if keyword_rule else None)
        or ("fallback" if recognized_package or recognized_category else None)
    )
    matched_by = (
        (model_rule.id if model_rule else None)
        or (keyword_rule.id if keyword_rule else None)
        or ("package_pattern" if normalized_package else None)
    )

    if not any((recognized_package, recognized_category, recognized_vendor, recognized_model_family)):
        return None

    category_path = recognized_category
    official_url = _build_lcsc_product_url(values.sku)
    return PartLookupResponse(
        found=True,
        source="local_rules",
        sku=values.sku,
        name=values.name,
        mpn=values.mpn,
        package_name=recognized_package,
        category=recognized_category,
        category_path=category_path,
        brand=values.brand,
        vendor=recognized_vendor,
        model_family=recognized_model_family,
        official_url=official_url,
        matched_by=matched_by,
        confidence=confidence or "fallback",
        cache_hit=False,
        rule_version=rules.version,
    )


def _detect_package(
    rules: RecognitionRuleSet,
    values: RecognitionValues,
) -> str | None:
    for pattern in rules.package_patterns:
        if pattern.compiled.search(values.combined):
            return pattern.normalized
    for token in _iter_compact_tokens(values):
        for pattern in rules.package_patterns:
            normalized_key = pattern.normalized.replace("-", "").casefold()
            if not normalized_key:
                continue
            if token == normalized_key:
                return pattern.normalized
            if token.startswith(normalized_key) and len(token) > len(normalized_key):
                return pattern.normalized
    return None


def _iter_compact_tokens(values: RecognitionValues) -> tuple[str, ...]:
    raw_values = (
        values.sku,
        values.mpn,
        values.name,
        values.brand,
        values.package_hint,
        values.source_type,
    )
    tokens: list[str] = []
    for raw_value in raw_values:
        if not raw_value:
            continue
        for token in re.split(r"[^A-Za-z0-9+-]+", raw_value):
            compact = token.replace("-", "").strip().casefold()
            if compact:
                tokens.append(compact)
    return tuple(tokens)


def _merge_lcsc_and_local(
    values: RecognitionValues,
    lcsc_result: LcscLookupResponse,
    local_result: PartLookupResponse | None,
) -> PartLookupResponse:
    source = lcsc_result.source
    if local_result is not None:
        source = f"{lcsc_result.source}+local_rules"

    return PartLookupResponse(
        found=True,
        source=source,
        sku=lcsc_result.sku or values.sku or (local_result.sku if local_result else None),
        name=lcsc_result.name or values.name or (local_result.name if local_result else None),
        mpn=lcsc_result.mpn or values.mpn or (local_result.mpn if local_result else None),
        package_name=lcsc_result.package_name or (
            local_result.package_name if local_result else values.package_hint
        ),
        category=lcsc_result.category or (local_result.category if local_result else None),
        category_path=lcsc_result.category_path or (
            local_result.category_path if local_result else None
        ),
        brand=lcsc_result.brand or values.brand or (local_result.brand if local_result else None),
        description=lcsc_result.description,
        parameters=lcsc_result.parameters,
        vendor=(
            local_result.vendor
            if local_result and local_result.vendor
            else lcsc_result.brand or values.brand
        ),
        model_family=local_result.model_family if local_result else None,
        official_url=lcsc_result.official_url or (
            local_result.official_url if local_result else None
        ),
        matched_by=lcsc_result.matched_by or (
            local_result.matched_by if local_result else None
        ),
        confidence=(
            lcsc_result.confidence
            if lcsc_result.confidence != "none"
            else local_result.confidence
            if local_result
            else "fallback"
        ),
        cache_hit=lcsc_result.cache_hit,
        rule_version=local_result.rule_version if local_result else None,
    )


def lookup_public_lcsc_product(
    settings: Settings,
    sku: str | None = None,
    mpn: str | None = None,
    name: str | None = None,
) -> LcscLookupResponse:
    normalized_queries = (
        ("sku", _normalize_text(sku)),
        ("mpn", _normalize_text(mpn)),
        ("name", _normalize_text(name)),
    )
    request_error: Exception | None = None
    for matched_by, query in normalized_queries:
        if not query:
            continue
        cache_key = f"{matched_by}:{query.casefold()}"
        cached = _read_public_web_cache(settings, cache_key)
        if cached is not None:
            return cached

        try:
            if matched_by == "sku" and _build_lcsc_product_url(query):
                response = _lookup_public_web_detail(query, matched_by)
            else:
                response = _lookup_public_web_search(query, matched_by)
        except (OSError, ValueError) as error:
            request_error = error
            continue

        if response is None or not response.found:
            continue

        _write_public_web_cache(settings, cache_key, response)
        return response

    if request_error is not None:
        raise LookupRequestError(
            f"Public LCSC lookup failed ({type(request_error).__name__})."
        ) from request_error
    return LcscLookupResponse(found=False)


def _lookup_public_web_detail(
    sku: str,
    matched_by: str,
) -> LcscLookupResponse | None:
    url = _build_lcsc_product_url(sku)
    if url is None:
        return None
    try:
        html = _fetch_public_web_text(url)
    except HTTPError as error:
        if error.code == 404:
            return None
        raise
    return _parse_public_web_lookup(
        html=html,
        source_url=url,
        matched_by=matched_by,
        query=sku,
        fallback_sku=sku,
    )


def _lookup_public_web_search(
    query: str,
    matched_by: str,
) -> LcscLookupResponse | None:
    search_url = f"https://www.lcsc.com/search?q={quote(query)}"
    html = _fetch_public_web_text(search_url)
    resolved_sku = _extract_first_search_sku(html)
    if resolved_sku:
        return _lookup_public_web_detail(resolved_sku, matched_by)
    return _parse_public_web_lookup(
        html=html,
        source_url=search_url,
        matched_by=matched_by,
        query=query,
        fallback_sku=None,
    )


def _fetch_public_web_text(url: str) -> str:
    request = Request(
        url,
        headers={
            "User-Agent": "ComponentVault/0.3.5 (+https://github.com/)",
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        },
    )
    with urlopen(request, timeout=12) as response:
        return response.read().decode("utf-8", errors="ignore")


def _parse_public_web_lookup(
    html: str,
    source_url: str,
    matched_by: str,
    query: str,
    fallback_sku: str | None,
) -> LcscLookupResponse | None:
    payloads = _extract_json_payloads(html)
    records = [
        record
        for payload in payloads
        for record in _iter_json_objects(payload)
        if _looks_like_public_product_record(record)
    ]
    record = _select_public_product_record(records, "sku" if fallback_sku else matched_by, query)
    category_path = _extract_breadcrumb_path(payloads)

    sku = _lookup_record_text(record, _SKU_KEYS)
    if record is None or (fallback_sku and (not sku or sku.casefold() != fallback_sku.casefold())):
        return None
    mpn = _lookup_record_text(record, _MPN_KEYS)
    name = _lookup_record_text(record, _NAME_KEYS)
    package_name = _lookup_record_text(record, _PACKAGE_KEYS)
    category = _lookup_record_text(record, _CATEGORY_KEYS)
    brand = _lookup_record_text(record, _BRAND_KEYS)

    if name is None:
        name = _clean_title_text(
            _extract_meta_value(html, "og:title")
            or _extract_meta_value(html, "twitter:title")
            or _extract_title_text(html),
        )
    if category_path and not category:
        category = category_path.split("/")[-1].strip()

    if not any((sku, mpn, name, package_name, category, brand, category_path)):
        return None

    confidence = "fallback"
    if matched_by == "sku" and sku and sku.casefold() == query.casefold():
        confidence = "exact"
    elif matched_by == "mpn" and mpn and mpn.casefold() == query.casefold():
        confidence = "exact"

    return LcscLookupResponse(
        found=True,
        source="lcsc_public_web",
        sku=sku,
        name=" ".join(value for value in (brand, mpn) if value) if mpn else sku,
        mpn=mpn,
        package_name=package_name,
        category=category,
        category_path=category_path,
        brand=brand,
        description=_lookup_record_text(record, ("description", "lightProductName")) or name,
        parameters=_extract_product_parameters(record),
        official_url=_build_lcsc_product_url(sku) or source_url,
        matched_by=matched_by,
        confidence=confidence,
        cache_hit=False,
    )


def _extract_product_parameters(record: dict[str, Any]) -> dict[str, str]:
    """Retain explicit upstream values without inferring specifications from an MPN."""
    parameters: dict[str, str] = {}
    for field in ("paramLinkedMap", "parameters"):
        raw = record.get(field)
        if isinstance(raw, dict):
            for key, value in raw.items():
                if isinstance(value, (str, int, float)) and not isinstance(value, bool):
                    text = str(value).strip()
                    if str(key).strip() and text:
                        parameters[str(key).strip()] = text
    properties = record.get("additionalProperty", [])
    if isinstance(properties, list):
        for prop in properties:
            if not isinstance(prop, dict):
                continue
            key, value = prop.get("name"), prop.get("value")
            if isinstance(key, str) and isinstance(value, (str, int, float)):
                if key.strip() and str(value).strip():
                    parameters.setdefault(key.strip(), str(value).strip())
    return parameters


def _read_public_web_cache(
    settings: Settings,
    cache_key: str,
) -> LcscLookupResponse | None:
    ttl = max(settings.lcsc_lookup_cache_ttl_seconds, 0)
    if ttl <= 0:
        return None
    now = time.time()
    with _PUBLIC_WEB_CACHE_LOCK:
        cached = _PUBLIC_WEB_CACHE.get(cache_key)
        if cached is None:
            return None
        expires_at, response = cached
        if expires_at <= now:
            _PUBLIC_WEB_CACHE.pop(cache_key, None)
            return None
        return response.model_copy(update={"cache_hit": True})


def _write_public_web_cache(
    settings: Settings,
    cache_key: str,
    response: LcscLookupResponse,
) -> None:
    ttl = max(settings.lcsc_lookup_cache_ttl_seconds, 0)
    if ttl <= 0 or not response.found:
        return
    with _PUBLIC_WEB_CACHE_LOCK:
        _PUBLIC_WEB_CACHE[cache_key] = (
            time.time() + ttl,
            response.model_copy(update={"cache_hit": False}),
        )


def _extract_json_payloads(html: str) -> list[Any]:
    payloads: list[Any] = []
    for match in _SCRIPT_BLOCK_PATTERN.finditer(html):
        raw_body = unescape(match.group("body").strip())
        if not raw_body:
            continue
        candidate = _extract_json_candidate(raw_body)
        if candidate is None:
            continue
        try:
            payloads.append(json.loads(candidate))
        except json.JSONDecodeError:
            continue
    return payloads


def _extract_json_candidate(script_body: str) -> str | None:
    if script_body.startswith("{") or script_body.startswith("["):
        return script_body
    first_brace = script_body.find("{")
    last_brace = script_body.rfind("}")
    if first_brace >= 0 and last_brace > first_brace:
        return script_body[first_brace : last_brace + 1]
    first_bracket = script_body.find("[")
    last_bracket = script_body.rfind("]")
    if first_bracket >= 0 and last_bracket > first_bracket:
        return script_body[first_bracket : last_bracket + 1]
    return None


def _iter_json_objects(value: Any):
    if isinstance(value, dict):
        yield value
        for nested in value.values():
            yield from _iter_json_objects(nested)
    elif isinstance(value, list):
        for item in value:
            yield from _iter_json_objects(item)


def _looks_like_public_product_record(record: dict[str, Any]) -> bool:
    record_type = _coerce_lookup_text(record.get("@type"))
    if record_type and record_type.casefold() == "product":
        return True
    return any(key in record for key in _SKU_KEYS + _MPN_KEYS + _PACKAGE_KEYS)


def _select_public_product_record(
    records: list[dict[str, Any]],
    matched_by: str,
    query: str,
) -> dict[str, Any] | None:
    normalized_query = query.casefold()
    fuzzy_match: dict[str, Any] | None = None
    for record in records:
        sku = _lookup_record_text(record, _SKU_KEYS)
        mpn = _lookup_record_text(record, _MPN_KEYS)
        name = _lookup_record_text(record, _NAME_KEYS)
        if matched_by == "sku" and sku and sku.casefold() == normalized_query:
            return record
        if matched_by == "mpn" and mpn and mpn.casefold() == normalized_query:
            return record
        if matched_by == "name" and name and normalized_query in name.casefold():
            return record
        fuzzy_match = fuzzy_match or record
    return fuzzy_match


def _lookup_record_text(
    record: dict[str, Any] | None,
    keys: tuple[str, ...],
) -> str | None:
    if record is None:
        return None
    lowered_keys = {key.casefold() for key in keys}
    for key, value in record.items():
        if key.casefold() not in lowered_keys:
            continue
        text_value = _coerce_lookup_text(value)
        if text_value:
            return text_value
    return None


def _coerce_lookup_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, dict):
        for nested_key in ("name", "value", "text", "label"):
            nested_value = _coerce_lookup_text(value.get(nested_key))
            if nested_value:
                return nested_value
        return None
    if isinstance(value, list):
        for item in value:
            nested_value = _coerce_lookup_text(item)
            if nested_value:
                return nested_value
        return None
    return _normalize_text(_coerce_text(value))


def _extract_breadcrumb_path(payloads: list[Any]) -> str | None:
    for record in (item for payload in payloads for item in _iter_json_objects(payload)):
        record_type = _coerce_lookup_text(record.get("@type"))
        if not record_type or record_type.casefold() != "breadcrumblist":
            continue
        segments: list[str] = []
        for item in record.get("itemListElement", []):
            if not isinstance(item, dict):
                continue
            label = _coerce_lookup_text(item.get("name"))
            if label is None and isinstance(item.get("item"), dict):
                label = _coerce_lookup_text(item["item"].get("name"))
            if label and label not in segments:
                segments.append(label)
        if segments:
            return " / ".join(segments)
    return None


def _extract_meta_value(
    html: str,
    key: str,
) -> str | None:
    normalized_key = key.casefold()
    for match in _META_TAG_PATTERN.finditer(html):
        if match.group("key").casefold() != normalized_key:
            continue
        return _clean_title_text(unescape(match.group("value")))
    return None


def _extract_title_text(html: str) -> str | None:
    match = _TITLE_PATTERN.search(html)
    if match is None:
        return None
    return _clean_title_text(unescape(match.group("value")))


def _clean_title_text(value: str | None) -> str | None:
    normalized = _normalize_text(value)
    if normalized is None:
        return None
    normalized = re.sub(r"\s+", " ", normalized)
    normalized = re.sub(r"\s*[-|]\s*LCSC.*$", "", normalized, flags=re.IGNORECASE)
    normalized = re.sub(r"\s*[-|]\s*JLC.*$", "", normalized, flags=re.IGNORECASE)
    return normalized.strip() or None


def _extract_first_search_sku(html: str) -> str | None:
    match = _DETAIL_LINK_PATTERN.search(html)
    if match is None:
        return None
    return match.group("sku").upper()


def _load_rules(
    settings: Settings,
    allow_refresh: bool = True,
) -> RecognitionRuleSet:
    override_path = _override_rules_path(settings)
    if allow_refresh and _should_refresh_remote(settings, override_path):
        try:
            refresh_recognition_rules(settings)
        except RecognitionRulesRefreshError:
            pass

    if override_path.exists():
        payload = json.loads(override_path.read_text(encoding="utf-8"))
        return _build_rule_set(
            payload,
            source="remote_cache",
            active_path=override_path,
            override_path=override_path,
        )

    bundled_path = Path(__file__).with_name("recognition_rules.json")
    payload = json.loads(bundled_path.read_text(encoding="utf-8"))
    return _build_rule_set(
        payload,
        source="bundled",
        active_path=bundled_path,
        override_path=override_path,
    )


def _should_refresh_remote(
    settings: Settings,
    override_path: Path,
) -> bool:
    if not settings.import_rules_remote_url.strip():
        return False
    refresh_hours = max(settings.import_rules_refresh_hours, 1)
    if not override_path.exists():
        return True
    file_age_seconds = max(0.0, time.time() - override_path.stat().st_mtime)
    return file_age_seconds >= refresh_hours * 3600


def _build_rule_set(
    payload: dict[str, Any],
    source: str,
    active_path: Path,
    override_path: Path,
) -> RecognitionRuleSet:
    package_patterns = []
    for item in payload.get("package_patterns", []):
        if not isinstance(item, dict):
            continue
        normalized = _normalize_text(_coerce_text(item.get("normalized")))
        pattern = _normalize_text(_coerce_text(item.get("pattern")))
        if not normalized or not pattern:
            continue
        package_patterns.append(
            PackagePattern(
                normalized=normalized,
                compiled=re.compile(pattern, re.IGNORECASE),
            ),
        )

    model_patterns = []
    for index, item in enumerate(payload.get("model_patterns", [])):
        if not isinstance(item, dict):
            continue
        match_type = _normalize_text(_coerce_text(item.get("match_type"))) or "regex"
        pattern = _normalize_text(_coerce_text(item.get("pattern"))) or ""
        compiled = re.compile(pattern, re.IGNORECASE) if match_type == "regex" and pattern else None
        model_patterns.append(
            ModelPattern(
                id=_normalize_text(_coerce_text(item.get("id"))) or f"rule_{index}",
                match_type=match_type,
                pattern=pattern,
                vendor=_normalize_text(_coerce_text(item.get("vendor"))),
                model_family=_normalize_text(_coerce_text(item.get("model_family"))),
                package_name=_normalize_text(_coerce_text(item.get("package_name"))),
                category=_normalize_text(_coerce_text(item.get("category"))),
                confidence=_normalize_text(_coerce_text(item.get("confidence"))),
                priority=int(item.get("priority", 0) or 0),
                compiled=compiled,
            ),
        )

    keyword_rules = []
    for index, item in enumerate(payload.get("keyword_rules", [])):
        if not isinstance(item, dict):
            continue
        keywords = tuple(
            keyword
            for keyword in (
                _normalize_text(_coerce_text(raw_keyword))
                for raw_keyword in item.get("keywords", [])
            )
            if keyword
        )
        if not keywords:
            continue
        keyword_rules.append(
            KeywordRule(
                id=_normalize_text(_coerce_text(item.get("id"))) or f"keyword_{index}",
                keywords=keywords,
                vendor=_normalize_text(_coerce_text(item.get("vendor"))),
                model_family=_normalize_text(_coerce_text(item.get("model_family"))),
                category=_normalize_text(_coerce_text(item.get("category"))),
                confidence=_normalize_text(_coerce_text(item.get("confidence"))),
                priority=int(item.get("priority", 0) or 0),
            ),
        )

    return RecognitionRuleSet(
        version=_normalize_text(_coerce_text(payload.get("version"))) or "unknown",
        updated_at=_normalize_text(_coerce_text(payload.get("updated_at"))),
        source=source,
        active_path=active_path,
        override_path=override_path,
        package_patterns=tuple(package_patterns),
        model_patterns=tuple(model_patterns),
        keyword_rules=tuple(keyword_rules),
    )


def _override_rules_path(settings: Settings) -> Path:
    database_parent = Path(settings.database_path).expanduser().resolve().parent
    return database_parent / "recognition_rules_cache.json"


def _build_lcsc_product_url(sku: str | None) -> str | None:
    if not sku:
        return None
    if re.fullmatch(r"C\d+", sku, flags=re.IGNORECASE):
        return f"https://www.lcsc.com/product-detail/{sku.upper()}.html"
    return None


def _coerce_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float)):
        return str(value)
    return None


def _normalize_text(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return normalized or None
