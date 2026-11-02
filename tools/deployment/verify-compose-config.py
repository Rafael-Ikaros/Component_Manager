#!/usr/bin/env python3
"""Verify that Compose renders the first-run deployment contract safely."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILES = (ROOT / "docker-compose.yml", ROOT / "docker-compose.hub.yml")
ENV_KEYS = ("API_TOKEN", "ADMIN_WEB_ORIGINS", "WEB_INVENTORY_ENABLED")
IMAGE_ENV_KEYS = (
    "COMPONENT_VAULT_DOCKER_CHANNEL", "COMPONENT_VAULT_IMAGE", "COMPONENT_VAULT_WEB_IMAGE",
)


def render(compose_file: Path, env_file: str, overrides: dict[str, str] | None = None) -> dict:
    command = [
        "docker", "compose", "--profile", "*", "--env-file", env_file,
        "-f", str(compose_file), "config", "--format", "json",
    ]
    environment = os.environ.copy()
    for key in (*ENV_KEYS, *IMAGE_ENV_KEYS):
        environment.pop(key, None)
    environment.update(overrides or {})
    result = subprocess.run(
        command,
        cwd=ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )
    # Never print rendered Compose output: it may contain deployment secrets.
    return json.loads(result.stdout)


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        fake_env = Path(directory) / "fake.env"
        fake_env.write_text(
            "API_TOKEN=compose-ci-token\n"
            "ADMIN_WEB_ORIGINS=http://localhost:8081\n"
            "WEB_INVENTORY_ENABLED=true\n",
            encoding="utf-8",
        )
        for compose_file in COMPOSE_FILES:
            empty = render(compose_file, os.devnull)
            overridden = render(compose_file, str(fake_env))
            api_empty = empty["services"]["api"]
            api_override = overridden["services"]["api"]
            empty_environment = api_empty["environment"]
            override_environment = api_override["environment"]
            for key in (*ENV_KEYS, "DATABASE_PATH"):
                assert key in empty_environment, (
                    f"{compose_file.name} api environment is missing {key}"
                )
            assert all(empty_environment[key] in (None, "") for key in ENV_KEYS)
            assert override_environment["API_TOKEN"] == "compose-ci-token"
            assert override_environment["ADMIN_WEB_ORIGINS"] == "http://localhost:8081"
            assert override_environment["WEB_INVENTORY_ENABLED"] in (True, "true")
            assert any(volume.get("target") == "/data" for volume in api_empty["volumes"])
            if compose_file.name == "docker-compose.hub.yml":
                web = empty["services"]["admin-web"]
                assert web.get("image") and "web" in web.get("profiles", [])
                for channel in ("latest", "dev"):
                    selected = render(compose_file, os.devnull, {
                        "COMPONENT_VAULT_DOCKER_CHANNEL": channel,
                    })
                    assert selected["services"]["api"]["image"] == (
                        f"rafaelikaros/component_manager:{channel}"
                    )
                    assert selected["services"]["admin-web"]["image"] == (
                        f"rafaelikaros/component_manager:web-{channel}"
                    )
                    assert selected["services"]["api"]["volumes"] == api_empty["volumes"]
                assert api_empty["image"] == "rafaelikaros/component_manager:latest"
                assert web["image"] == "rafaelikaros/component_manager:web-latest"
                pinned = render(compose_file, os.devnull, {
                    "COMPONENT_VAULT_DOCKER_CHANNEL": "dev",
                    "COMPONENT_VAULT_IMAGE": "example/inventory:0.7.9",
                    "COMPONENT_VAULT_WEB_IMAGE": "example/inventory:web-0.7.9",
                })
                assert pinned["services"]["api"]["image"] == "example/inventory:0.7.9"
                assert pinned["services"]["admin-web"]["image"] == "example/inventory:web-0.7.9"
    print("Compose first-run configuration schema: OK")


if __name__ == "__main__":
    main()
