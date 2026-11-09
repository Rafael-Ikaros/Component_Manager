from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import json
import os
from pathlib import Path
import re
import sqlite3
from urllib.parse import quote, urlsplit


DEFAULT_ADMIN_WEB_ORIGINS = (
    "http://localhost:5173", "http://127.0.0.1:5173",
    "http://localhost:8081", "http://127.0.0.1:8081",
)
PERSISTED_ENVIRONMENT_FIELDS = {
    "api_token": "API_TOKEN",
    "admin_web_origins": "ADMIN_WEB_ORIGINS",
    "admin_web_url": "ADMIN_WEB_URL",
    "web_inventory_enabled": "WEB_INVENTORY_ENABLED",
}


class ConfigurationError(RuntimeError):
    """The persisted deployment configuration cannot be used safely."""


def normalize_admin_web_url(value: str) -> str:
    candidate = value.strip()
    if not candidate:
        return ""
    parsed = urlsplit(candidate)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.netloc
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or any(character.isspace() for character in candidate)
    ):
        raise ValueError("Admin web URL must be an absolute HTTP(S) URL without credentials, query, or fragment.")
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("Admin web URL has an invalid port.") from error
    return candidate


@dataclass(frozen=True)
class Settings:
    app_name: str
    api_token: str
    database_path: str
    app_host: str
    app_port: int
    admin_web_origins: tuple[str, ...]
    lcsc_lookup_cache_ttl_seconds: int
    import_rules_remote_url: str
    import_rules_refresh_hours: int
    enable_web_fallback_resolvers: bool
    web_inventory_enabled: bool = False
    admin_web_url: str = ""
    mqtt_enabled: bool = False
    mqtt_host: str = ""
    mqtt_port: int = 1883
    mqtt_tls: bool = False
    mqtt_username: str = ""
    mqtt_password: str = ""
    mqtt_topic_prefix: str = "component-vault"
    mqtt_client_id: str = "component-vault-server"


def database_path() -> Path:
    return Path(os.getenv("DATABASE_PATH", "./data/component_vault.db"))


def config_path() -> Path:
    override = os.getenv("CONFIG_PATH", "").strip()
    return Path(override) if override else database_path().parent / "config.json"


def log_path() -> Path:
    override = os.getenv("LOG_DIR", "").strip()
    directory = Path(override) if override else database_path().parent / "logs"
    return directory / "server.log"


def environment_overrides() -> tuple[str, ...]:
    return tuple(
        name for name in PERSISTED_ENVIRONMENT_FIELDS.values()
        if os.getenv(name, "").strip()
    )


def read_persisted_configuration() -> dict[str, object]:
    path = config_path()
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ConfigurationError(
            f"Cannot read deployment configuration at {path} "
            f"({type(error).__name__})."
        ) from error
    if not isinstance(raw, dict):
        raise ConfigurationError(f"Deployment configuration at {path} must be a JSON object.")
    unexpected = set(raw) - set(PERSISTED_ENVIRONMENT_FIELDS.values())
    if unexpected:
        raise ConfigurationError(
            f"Deployment configuration at {path} contains unsupported fields: "
            f"{', '.join(sorted(unexpected))}."
        )
    token = raw.get("API_TOKEN", "")
    origins = raw.get("ADMIN_WEB_ORIGINS", list(DEFAULT_ADMIN_WEB_ORIGINS))
    admin_web_url = raw.get("ADMIN_WEB_URL", "")
    enabled = raw.get("WEB_INVENTORY_ENABLED", False)
    if not isinstance(token, str):
        raise ConfigurationError("Saved api_token must be a string.")
    if not isinstance(origins, list) or not all(
        isinstance(item, str) and item.strip() for item in origins
    ):
        raise ConfigurationError("Saved admin_web_origins must be a list of URLs.")
    if not isinstance(enabled, bool):
        raise ConfigurationError("Saved web_inventory_enabled must be a boolean.")
    if not isinstance(admin_web_url, str):
        raise ConfigurationError("Saved admin_web_url must be a string.")
    try:
        admin_web_url = normalize_admin_web_url(admin_web_url)
    except ValueError as error:
        raise ConfigurationError(f"Saved admin_web_url is invalid: {error}") from error
    return {
        "api_token": token,
        "admin_web_origins": origins,
        "admin_web_url": admin_web_url,
        "web_inventory_enabled": enabled,
    }


def _legacy_database_has_business_data(path: Path) -> bool:
    if not path.is_file():
        return False
    uri = f"file:{quote(path.resolve().as_posix(), safe='/:')}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
        try:
            tables = {
                row[0] for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'"
                )
            }
            for table in ("components", "stock_movements", "storage_locations"):
                if table in tables and connection.execute(
                    f"SELECT 1 FROM {table} LIMIT 1"
                ).fetchone():
                    return True
        finally:
            connection.close()
    except sqlite3.Error as error:
        raise ConfigurationError(
            f"Cannot inspect existing database at {path} "
            f"({type(error).__name__}); restore it or set API_TOKEN explicitly."
        ) from error
    return False


def _csv_value(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


def _bool_value(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _bool_env(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    return default if value is None else _bool_value(value)


def validate_mqtt_settings(values: dict[str, object]) -> dict[str, object]:
    enabled = bool(values["mqtt_enabled"])
    host = str(values["mqtt_host"]).strip()
    port = int(values["mqtt_port"])
    prefix = str(values["mqtt_topic_prefix"]).strip().strip("/")
    client_id = str(values["mqtt_client_id"]).strip()
    if not 1 <= port <= 65535:
        raise ValueError("MQTT_PORT must be between 1 and 65535.")
    if enabled and not host:
        raise ValueError("MQTT_HOST is required when MQTT is enabled.")
    if host and (
        re.search(r"[\x00-\x20\x7f]", host)
        or "://" in host
        or any(character in host for character in "@/\\?#")
    ):
        raise ValueError(
            "MQTT_HOST must be a domain, IP address, or IPv6 address "
            "without a URL scheme or path."
        )
    if not prefix or "+" in prefix or "#" in prefix:
        raise ValueError("MQTT_TOPIC_PREFIX must be non-empty and contain no wildcards.")
    if re.search(r"[\x00-\x1f\x7f]", prefix):
        raise ValueError("MQTT_TOPIC_PREFIX must contain no control characters.")
    if not client_id:
        raise ValueError("MQTT_CLIENT_ID must be non-empty.")
    if re.search(r"[\x00-\x1f\x7f]", client_id):
        raise ValueError("MQTT_CLIENT_ID must contain no control characters.")
    return {
        "mqtt_enabled": enabled, "mqtt_host": host, "mqtt_port": port,
        "mqtt_tls": bool(values["mqtt_tls"]),
        "mqtt_username": str(values["mqtt_username"]).strip(),
        "mqtt_password": str(values["mqtt_password"]),
        "mqtt_topic_prefix": prefix, "mqtt_client_id": client_id,
    }


def _mqtt_env_settings() -> dict[str, object]:
    return validate_mqtt_settings({
        "mqtt_enabled": _bool_env("MQTT_ENABLED"),
        "mqtt_host": os.getenv("MQTT_HOST", ""),
        "mqtt_port": int(os.getenv("MQTT_PORT", "1883")),
        "mqtt_tls": _bool_env("MQTT_TLS"),
        "mqtt_username": os.getenv("MQTT_USERNAME", ""),
        "mqtt_password": os.getenv("MQTT_PASSWORD", ""),
        "mqtt_topic_prefix": os.getenv("MQTT_TOPIC_PREFIX", "component-vault"),
        "mqtt_client_id": os.getenv("MQTT_CLIENT_ID", "component-vault-server"),
    })


def _saved_mqtt_settings(path_value: str) -> dict[str, object] | None:
    path = Path(path_value)
    if not path.is_file():
        return None
    uri = f"file:{quote(path.resolve().as_posix(), safe='/:')}?mode=ro"
    try:
        connection = sqlite3.connect(uri, uri=True)
        connection.row_factory = sqlite3.Row
        try:
            exists = connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' "
                "AND name='mqtt_configuration'"
            ).fetchone()
            if not exists:
                return None
            row = connection.execute(
                "SELECT * FROM mqtt_configuration WHERE id = 1"
            ).fetchone()
        finally:
            connection.close()
    except sqlite3.Error:
        return None
    if row is None:
        return None
    return validate_mqtt_settings({
        "mqtt_enabled": bool(row["enabled"]), "mqtt_host": row["host"],
        "mqtt_port": row["port"], "mqtt_tls": bool(row["tls"]),
        "mqtt_username": row["username"], "mqtt_password": row["password"],
        "mqtt_topic_prefix": row["topic_prefix"], "mqtt_client_id": row["client_id"],
    })


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    db_path = str(database_path())
    persisted = read_persisted_configuration()
    api_token = os.getenv("API_TOKEN", "").strip() or str(
        persisted.get("api_token", "")
    ).strip()
    if not api_token and _legacy_database_has_business_data(Path(db_path)):
        api_token = "change-me"
    env_origins = os.getenv("ADMIN_WEB_ORIGINS", "").strip()
    saved_origins = persisted.get("admin_web_origins", DEFAULT_ADMIN_WEB_ORIGINS)
    origins = _csv_value(env_origins) if env_origins else tuple(saved_origins)
    try:
        admin_web_url = normalize_admin_web_url(
            os.getenv("ADMIN_WEB_URL", "").strip() or str(persisted.get("admin_web_url", ""))
        )
    except ValueError as error:
        raise ConfigurationError(f"ADMIN_WEB_URL is invalid: {error}") from error
    env_web_enabled = os.getenv("WEB_INVENTORY_ENABLED", "").strip()
    web_enabled = (_bool_value(env_web_enabled) if env_web_enabled else
                   bool(persisted.get("web_inventory_enabled", False)))
    mqtt_settings = _saved_mqtt_settings(db_path) or _mqtt_env_settings()
    return Settings(
        app_name="Component Vault Sync", api_token=api_token,
        database_path=db_path, app_host=os.getenv("APP_HOST", "0.0.0.0"),
        app_port=int(os.getenv("APP_PORT", "8787")),
        admin_web_origins=origins,
        admin_web_url=admin_web_url,
        lcsc_lookup_cache_ttl_seconds=int(
            os.getenv("LCSC_LOOKUP_CACHE_TTL_SECONDS", "43200")
        ),
        import_rules_remote_url=os.getenv("IMPORT_RULES_REMOTE_URL", "").strip(),
        import_rules_refresh_hours=int(os.getenv("IMPORT_RULES_REFRESH_HOURS", "24")),
        enable_web_fallback_resolvers=_bool_env("ENABLE_WEB_FALLBACK_RESOLVERS"),
        web_inventory_enabled=web_enabled, **mqtt_settings,
    )
