"""Rehearse the documented two-service Compose upgrade with synthetic inventory."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
from urllib.error import URLError
from urllib.request import Request, urlopen


BASELINE = "rafaelikaros/component_manager"
CANDIDATE = "component-vault-server:ci"
TOKEN = "isolated-upgrade-ci-token"


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True).strip()


def stage_template(source, target):
    template = source.read_text(encoding="utf-8")
    for port in ("8787:8787", "8081:80"):
        _, target_port = port.split(":")
        assert template.count(f'"{port}"') == 1, (source, port)
        template = template.replace(f'"{port}"', f'"127.0.0.1::{target_port}"')
    target.write_text(template, encoding="utf-8")


def http(base, path, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
    with urlopen(Request(base + path, data=body, headers=headers), timeout=5) as response:
        return response.read()


def wait_http(base, path):
    deadline = time.monotonic() + 90
    while True:
        try:
            return http(base, path)
        except (URLError, OSError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)


def verify_stack(compose, version, image_refs):
    api = "http://" + compose("port", "api", "8787")
    web = "http://" + compose("port", "admin-web", "80")
    health = json.loads(wait_http(api, "/health"))
    assert health["status"] == "ok" and health["version"].removeprefix("v") == version, health
    assert b"<title>Component Vault Admin</title>" in wait_http(web, "/"), "Unexpected Web page"

    containers = {}
    for service, image_ref in image_refs.items():
        container_id = compose("ps", "-q", service)
        container = json.loads(docker("inspect", container_id))[0]
        assert container["Image"] == docker("image", "inspect", "--format", "{{.Id}}", image_ref), service
        containers[service] = container
    mounts = containers["api"]["Mounts"]
    volume = next(mount["Name"] for mount in mounts if mount["Type"] == "volume" and mount["Destination"] == "/data")
    return api, health, volume


def rehearse(baseline_template, directory):
    compose_file = directory / "compose.yml"
    env_file = directory / ".env"
    project = directory.name
    command = ["compose", "-p", project, "--project-directory", str(directory), "--env-file", str(env_file),
               "-f", str(compose_file), "--profile", "web"]

    def compose(*args):
        return docker(*command, *args)

    def write_images(images):
        env_file.write_text(
            f"API_TOKEN={TOKEN}\nCOMPONENT_VAULT_IMAGE={images['api']}\n"
            f"COMPONENT_VAULT_WEB_IMAGE={images['admin-web']}\n",
            encoding="utf-8",
        )

    baseline_images = {"api": f"{BASELINE}:0.7.8", "admin-web": f"{BASELINE}:web-0.7.8"}
    candidate_images = dict.fromkeys(baseline_images, CANDIDATE)
    stage_template(baseline_template, compose_file)
    write_images(baseline_images)
    try:
        compose("pull", "api", "admin-web")
        compose("up", "-d", "--pull", "never")
        api, _, volume = verify_stack(compose, "0.7.8", baseline_images)
        timestamp = datetime.now(timezone.utc).isoformat()
        fixture = {
            "id": "upgrade-ci-component", "sku": "UPGRADE-CI-ONLY", "name": "Upgrade fixture",
            "category": "CI fixtures", "package_name": "0603", "location": "CI Drawer",
            "quantity": 37, "min_stock": 2, "updated_at": timestamp,
            "allocations": [{"location_id": "upgrade-ci-location", "quantity": 37}],
        }
        accepted = json.loads(http(api, "/sync/push", {
            "device_id": "isolated-upgrade-ci", "inventory_protocol": 1,
            "storage_locations": [{"id": "upgrade-ci-location", "name": "CI Drawer", "updated_at": timestamp}],
            "components": [fixture],
            "stock_movements": [{
                "id": "upgrade-ci-inbound", "component_id": fixture["id"], "movement_type": "inbound",
                "quantity": 37, "reason": "Seed isolated upgrade fixture", "happened_at": timestamp,
                "updated_at": timestamp, "location_id": "upgrade-ci-location",
            }],
        }))
        assert accepted["accepted_components"] == 1, accepted
        assert accepted["accepted_stock_movements"] == 1, accepted
        before = json.loads(http(api, "/sync/pull?cursor=0"))
        assert [item["id"] for item in before["components"]] == [fixture["id"]], before
        assert any(item["id"] == "upgrade-ci-location" for item in before["storage_locations"]), before

        compose("stop", "api")
        stage_template(Path("docker-compose.hub.yml"), compose_file)
        write_images(candidate_images)
        compose("up", "-d", "--pull", "never")
        version = os.environ["RELEASE_TAG"].removeprefix("v")
        api, health, upgraded_volume = verify_stack(compose, version, candidate_images)
        assert health["revision"] == os.environ["EXPECTED_REVISION"], health
        assert upgraded_volume == volume, (volume, upgraded_volume)
        after = json.loads(http(api, "/sync/pull?cursor=0"))
        assert len(after["components"]) == 1, after
        old_component, new_component = before["components"][0], after["components"][0]
        assert {key: new_component[key] for key in old_component} == old_component, (old_component, new_component)
        assert new_component["inventory_unit_cost"] is None, new_component
        assert after["storage_locations"] == before["storage_locations"], (before, after)
        assert len(before["stock_movements"]) == len(after["stock_movements"]) == 1, (before, after)
        old_movement, new_movement = before["stock_movements"][0], after["stock_movements"][0]
        assert {key: new_movement[key] for key in old_movement} == old_movement, (old_movement, new_movement)
        print(f"Compose 0.7.8 → {version} ({health['revision']}): API, Web, /data, inventory and stock history verified.")
    except Exception:
        subprocess.run(["docker", *command, "logs", "--no-color"], check=False)
        raise
    finally:
        subprocess.run(["docker", *command, "down", "--volumes", "--remove-orphans"], check=True)


if __name__ == "__main__":
    with TemporaryDirectory(prefix="cv-v078-upgrade-") as path:
        rehearse(Path(sys.argv[1]), Path(path))
