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
    "COMPONENT_VAULT_UPDATER_IMAGE", "COMPONENT_VAULT_DEPLOY_DIR", "COMPOSE_PROJECT_NAME",
)


def render(compose_file: Path, env_file: str, overrides: dict[str, str] | None = None,
           extra_files: tuple[Path, ...] = ()) -> dict:
    command = [
        "docker", "compose", "--profile", "*", "--env-file", env_file,
    ]
    for path in (compose_file, *extra_files):
        command.extend(("-f", str(path)))
    command.extend(("config", "--format", "json"))
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
                managed = render(compose_file, os.devnull, {
                    "COMPONENT_VAULT_DEPLOY_DIR": str(ROOT),
                    "COMPOSE_PROJECT_NAME": "component-vault-compose-ci",
                    "COMPONENT_VAULT_DOCKER_CHANNEL": "dev",
                }, (ROOT / "docker-compose.watchtower.yml", ROOT / "docker-compose.web-update.yml"))
                services = managed["services"]
                assert services["api"]["environment"]["UPDATER_URL"] == "http://updater:8790"
                assert services["api"]["environment"]["UPDATE_TOKEN_FILE"] == "/data/.updater-token"
                for service in ("api", "admin-web"):
                    assert services[service]["labels"]["com.centurylinklabs.watchtower.enable"] == "false"
                    assert not any(volume["target"] == "/var/run/docker.sock" for volume in services[service].get("volumes", []))
                updater = services["updater"]
                assert not updater.get("ports")
                assert updater["image"] == services["admin-web"]["image"]
                assert updater["command"] == ["updater"]
                assert updater["environment"]["UPDATE_PROJECT_NAME"] == "component-vault-compose-ci"
                assert updater["environment"]["UPDATE_TOKEN_FILE"] == "/data/.updater-token"
                assert any(volume["target"] == "/data" and volume.get("read_only") for volume in updater["volumes"])
                assert any(volume["target"] == "/var/run/docker.sock" for volume in updater["volumes"])
                assert any(volume["target"] == str(ROOT) and volume["source"] == str(ROOT) for volume in updater["volumes"])
                # Synology's GUI can use one complete file without manually
                # merging the Hub template and updater override. It starts Web
                # by default; the remaining services must render identically.
                single = render(ROOT / "docker-compose.hub-web-update.yml", os.devnull, {
                    "COMPONENT_VAULT_DEPLOY_DIR": str(ROOT),
                    "COMPOSE_PROJECT_NAME": "component-vault-compose-ci",
                    "COMPONENT_VAULT_DOCKER_CHANNEL": "dev",
                })
                combined = render(compose_file, os.devnull, {
                    "COMPONENT_VAULT_DEPLOY_DIR": str(ROOT),
                    "COMPOSE_PROJECT_NAME": "component-vault-compose-ci",
                    "COMPONENT_VAULT_DOCKER_CHANNEL": "dev",
                }, (ROOT / "docker-compose.web-update.yml",))
                combined["services"]["admin-web"].pop("profiles", None)
                assert single == combined, "Single-file Web updater deployment drifted from the base and override."
                # Explicit Web pins must also select the updater image, even if
                # an old deployment still has the retired updater override.
                for channel in ("latest", "dev"):
                    shared = render(ROOT / "docker-compose.hub-web-update.yml", os.devnull, {
                        "COMPONENT_VAULT_DEPLOY_DIR": str(ROOT),
                        "COMPOSE_PROJECT_NAME": "component-vault-compose-ci",
                        "COMPONENT_VAULT_DOCKER_CHANNEL": channel,
                        "COMPONENT_VAULT_WEB_IMAGE": "example/inventory:web-pinned",
                        "COMPONENT_VAULT_UPDATER_IMAGE": "example/inventory:updater-old",
                    })["services"]
                    assert shared["admin-web"]["image"] == "example/inventory:web-pinned"
                    assert shared["updater"]["image"] == shared["admin-web"]["image"]
                    assert shared["updater"]["command"] == ["updater"]
    print("Compose first-run configuration schema: OK")


if __name__ == "__main__":
    main()
