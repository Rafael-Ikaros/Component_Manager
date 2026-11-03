#!/usr/bin/env bash
# Post-publication acceptance test. Uses only its unique temporary CI project.
set -euo pipefail

: "${RUNNER_TEMP:?Run this check on the isolated GitHub Actions runner}"
: "${RELEASE_TAG:?Set the just-published release tag}"
: "${EXPECTED_REVISION:?Set the just-published commit SHA}"
UPDATE_CHANNEL=${UPDATE_CHANNEL:-dev}
case "$UPDATE_CHANNEL" in stable|dev) ;; *) echo 'Invalid UPDATE_CHANNEL' >&2; exit 1;; esac
repository=rafaelikaros/component_manager
project="cv-web-update-${GITHUB_RUN_ID:-local}-$$"
deployment=$(mktemp -d "$RUNNER_TEMP/component-vault-web-update.XXXXXX")
export UPDATE_CHANNEL EXPECTED_REVISION RELEASE_TAG
export SMOKE_PROJECT=$project SMOKE_DEPLOY_DIR=$deployment
read -r api_port web_port < <(python - <<'PY'
import socket
with socket.socket() as api, socket.socket() as web:
    api.bind(('127.0.0.1', 0))
    web.bind(('127.0.0.1', 0))
    print(api.getsockname()[1], web.getsockname()[1])
PY
)
export SMOKE_API_BASE="http://127.0.0.1:$api_port"
export SMOKE_WEB_BASE="http://127.0.0.1:$web_port"

docker tag component-vault-server:ci "$repository:updater-smoke-before"
docker tag component-vault-management:ci "$repository:web-updater-smoke-before"
cat > "$deployment/.env" <<EOF
COMPOSE_PROJECT_NAME=$project
SMOKE_DEPLOY_DIR=$deployment
SMOKE_API_PORT=$api_port
SMOKE_WEB_PORT=$web_port
API_TOKEN=ci-online-update-token
COMPONENT_VAULT_IMAGE=$repository:updater-smoke-before
COMPONENT_VAULT_WEB_IMAGE=$repository:web-updater-smoke-before
EOF
cat > "$deployment/compose.yml" <<'YAML'
services:
  api:
    image: ${COMPONENT_VAULT_IMAGE}
    environment:
      API_TOKEN: ${API_TOKEN}
      DATABASE_PATH: /data/component_vault.db
      UPDATER_URL: http://updater:8790
      UPDATE_TOKEN_FILE: /data/.updater-token
    ports:
      - "127.0.0.1:${SMOKE_API_PORT}:8787"
    volumes:
      - component_vault_data:/data
  admin-web:
    image: ${COMPONENT_VAULT_WEB_IMAGE}
    ports:
      - "127.0.0.1:${SMOKE_WEB_PORT}:80"
  updater:
    image: ${COMPONENT_VAULT_WEB_IMAGE}
    command: [updater]
    environment:
      UPDATE_PROJECT_NAME: ${COMPOSE_PROJECT_NAME}
      UPDATE_TOKEN_FILE: /data/.updater-token
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - component_vault_data:/data:ro
      - component_vault_updater_state:/state
      - ${SMOKE_DEPLOY_DIR}:${SMOKE_DEPLOY_DIR}:rw
volumes:
  component_vault_data:
  component_vault_updater_state:
YAML
compose=(docker compose --project-name "$project" --project-directory "$deployment"
    --env-file "$deployment/.env" -f "$deployment/compose.yml")
cleanup() {
    result=$?
    trap - EXIT INT TERM
    if [ "$result" -ne 0 ]; then "${compose[@]}" logs --no-color || true; fi
    "${compose[@]}" down --volumes --remove-orphans || true
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${compose[@]}" up -d --pull never

python - <<'PY'
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

base = os.environ['SMOKE_API_BASE']
directory = Path(os.environ['SMOKE_DEPLOY_DIR'])
compose = ['docker', 'compose', '--project-name', os.environ['SMOKE_PROJECT'],
           '--project-directory', str(directory), '--env-file', str(directory / '.env'),
           '-f', str(directory / 'compose.yml')]
headers = {'Authorization': 'Bearer ci-online-update-token', 'Content-Type': 'application/json'}

def request(path, data=None):
    body = json.dumps(data).encode() if data is not None else None
    with urlopen(Request(base + path, data=body, headers=headers), timeout=8) as response:
        return json.load(response)

def wait_for(action, description, timeout=480):
    deadline = time.monotonic() + timeout
    last_problem = ''
    while time.monotonic() < deadline:
        try:
            value = action()
            if value is not None:
                return value
        except HTTPError as error:
            if error.code not in (502, 503):
                raise
            last_problem = str(error)
        except (OSError, URLError) as error:
            last_problem = str(error)
        time.sleep(1)
    raise AssertionError(f'{description} timed out. {last_problem}')

def container(service):
    result = subprocess.check_output([*compose, 'ps', '-q', service], text=True).strip()
    assert result, f'{service} is missing'
    return result

def inspect(container_id):
    return json.loads(subprocess.check_output(['docker', 'inspect', container_id], text=True))[0]

def data_source(container_id):
    return next(mount['Source'] for mount in inspect(container_id)['Mounts'] if mount['Destination'] == '/data')

def web_ready():
    with urlopen(os.environ['SMOKE_WEB_BASE'], timeout=5) as response:
        assert response.status == 200
    return True

wait_for(lambda: request('/health'), 'Initial API startup')
status = wait_for(lambda: request('/admin-api/server-update/status'), 'Updater token handshake')
assert status['enabled'], status
wait_for(web_ready, 'Initial Web startup')
before = {service: container(service) for service in ('api', 'admin-web', 'updater')}
assert inspect(before['admin-web'])['Image'] == inspect(before['updater'])['Image'], 'Web and updater must start from the same image ID'
original_mount = data_source(before['api'])
original_ports = inspect(before['api'])['HostConfig']['PortBindings']
timestamp = datetime.now(timezone.utc).isoformat()
fixture = {
    'id': 'updater-ci-component', 'sku': 'UPDATER-CI-ONLY',
    'name': 'Updater CI fixture', 'category': 'CI fixtures', 'package_name': '0603',
    'location': 'CI Drawer', 'quantity': 37, 'min_stock': 2, 'updated_at': timestamp,
    'allocations': [{'location_id': 'updater-ci-location', 'quantity': 37}],
}
accepted = request('/sync/push', {
    'device_id': 'isolated-updater-ci', 'inventory_protocol': 1,
    'storage_locations': [{'id': 'updater-ci-location', 'name': 'CI Drawer', 'updated_at': timestamp}],
    'components': [fixture],
})
assert accepted['accepted_components'] == 1, accepted
snapshot = request('/sync/pull?cursor=0')
before_component = next(item for item in snapshot['components'] if item['id'] == fixture['id'])
assert all(before_component[key] == fixture[key] for key in ('name', 'quantity', 'location')), before_component
job_id = 'ci-real-compose-update'
job = wait_for(lambda: request('/admin-api/server-update/jobs', {
    'channel': os.environ['UPDATE_CHANNEL'], 'request_id': job_id,
}), 'Update submission')
assert job['id'] == job_id, job

def completed_job():
    job = request('/admin-api/server-update/jobs/' + job_id)
    assert job['status'] != 'failed', job
    return job if job['status'] == 'succeeded' else None

job = wait_for(completed_job, 'Real Compose update')
health = request('/health')
expected_version = os.environ['RELEASE_TAG'].removeprefix('v')
assert health['version'].removeprefix('v') == expected_version, health
assert health['revision'] == os.environ['EXPECTED_REVISION'], health
assert job['target_version'].removeprefix('v') == expected_version, job
after = {service: container(service) for service in before}
assert after['api'] != before['api'], 'API container was not replaced'
assert after['admin-web'] != before['admin-web'], 'Web container was not replaced'
assert after['updater'] == before['updater'], 'Updater must survive the API/Web replacement'
assert data_source(after['api']) == original_mount, 'API /data mount source changed'
assert inspect(after['api'])['HostConfig']['PortBindings'] == original_ports, 'API port bindings changed'
after_snapshot = request('/sync/pull?cursor=0')
after_component = next(item for item in after_snapshot['components'] if item['id'] == fixture['id'])
assert after_component == before_component, (before_component, after_component)
assert after_snapshot['storage_locations'] == snapshot['storage_locations'], 'Storage locations changed'
wait_for(web_ready, 'Updated Web startup')
channel = 'dev' if os.environ['UPDATE_CHANNEL'] == 'dev' else 'latest'
environment = (directory / '.env').read_text().splitlines()
assert f'COMPONENT_VAULT_DOCKER_CHANNEL={channel}' in environment
assert f'COMPONENT_VAULT_IMAGE=rafaelikaros/component_manager:{channel}' in environment
assert f'COMPONENT_VAULT_WEB_IMAGE=rafaelikaros/component_manager:web-{channel}' in environment
print('Real Web update: API/Web replaced, updater retained, selected release healthy, fixture and /data preserved.')
PY
