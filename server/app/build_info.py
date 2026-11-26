"""Build identity baked into container images; source runs remain explicit."""

import json
from pathlib import Path


try:
    build = json.loads(Path(__file__).with_name("_container_build.json").read_text(encoding="utf-8"))
except FileNotFoundError:
    build = {"version": "source", "revision": "unknown"}

BUILD_VERSION = build["version"]
BUILD_REVISION = build["revision"]
