"""Resolve and validate fixed Docker Hub tags without writing to the registry."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
ACCEPT = ", ".join((
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.docker.distribution.manifest.v1+prettyjws",
    "application/vnd.docker.distribution.manifest.v1+json",
))


def registry_token(repository, username, password):
    query = urlencode({"service": "registry.docker.io", "scope": f"repository:{repository}:pull"})
    credentials = base64.b64encode(f"{username}:{password}".encode()).decode()
    request = Request(f"https://auth.docker.io/token?{query}")
    request.add_unredirected_header("Authorization", f"Basic {credentials}")
    with urlopen(request, timeout=20) as response:
        return json.load(response)["token"]


def tag_digest(repository, tag, token):
    request = Request(f"https://registry-1.docker.io/v2/{repository}/manifests/{tag}", headers={"Accept": ACCEPT})
    request.add_unredirected_header("Authorization", f"Bearer {token}")
    try:
        with urlopen(request, timeout=20) as response:
            if response.status != 200:
                raise RuntimeError(f"Could not resolve {repository}:{tag} (HTTP {response.status}).")
            digest = response.headers.get("Docker-Content-Digest", "")
    except HTTPError as error:
        with error:
            if error.code == 404:
                errors = json.load(error).get("errors", [])
                # A content-negotiation error can also use MANIFEST_UNKNOWN,
                # but has a different message from an absent Docker Hub tag.
                if (len(errors) == 1 and errors[0].get("code") == "MANIFEST_UNKNOWN"
                        and errors[0].get("message") == "manifest unknown"):
                    return None
        raise RuntimeError(f"Could not resolve {repository}:{tag} (HTTP {error.code}).") from error
    if not DIGEST.fullmatch(digest):
        raise RuntimeError(f"Docker Hub did not return a valid digest for {repository}:{tag}.")
    return digest


def verify_image(repository, tag, digest, version, revision):
    output = subprocess.check_output([
        "docker", "buildx", "imagetools", "inspect", f"{repository}@{digest}", "--format", "{{json .Image}}",
    ], text=True)
    configs = [config for config in json.loads(output).values()
               if isinstance(config, dict) and config.get("os") == "linux"]
    if {config.get("architecture") for config in configs} != {"amd64", "arm64"}:
        raise RuntimeError(f"{repository}:{tag} must include linux/amd64 and linux/arm64.")
    for config in configs:
        labels = config.get("config", {}).get("Labels", {})
        if (labels.get("org.opencontainers.image.version") != version
                or labels.get("org.opencontainers.image.revision") != revision):
            raise RuntimeError(f"{repository}:{tag} ({config['architecture']}) has unexpected release identity.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expect-digests", nargs=2, metavar=("API_DIGEST", "WEB_DIGEST"))
    args = parser.parse_args()
    repository = os.environ["DOCKERHUB_IMAGE"]
    version = os.environ["RELEASE_TAG"]
    revision = os.environ["EXPECTED_REVISION"]
    token = registry_token(repository, os.environ["DOCKERHUB_USERNAME"], os.environ["DOCKERHUB_TOKEN"])
    dev = "-dev." in version
    channel = "dev" if dev else "latest"
    outputs = {"update_channel": "dev" if dev else "stable"}
    for index, (role, prefix) in enumerate((("api", ""), ("web", "web-"))):
        tag = prefix + version[1:]
        digest = tag_digest(repository, tag, token)
        if args.expect_digests is not None and (digest is None or digest != args.expect_digests[index]):
            raise RuntimeError(f"{repository}:{tag} did not resolve to the expected digest.")
        if digest is None:
            print(f"Missing fixed tag {repository}:{tag}.")
        else:
            verify_image(repository, tag, digest, version, revision)
            print(f"Verified fixed tag {repository}:{tag} at {digest}.")
        outputs[f"{role}_digest"] = digest or ""
        outputs[f"{role}_missing"] = "true" if digest is None else "false"
        outputs[f"{role}_version"] = f"{repository}:{tag}"
        outputs[f"{role}_channel"] = f"{repository}:{prefix}{channel}"
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
        for name, value in outputs.items():
            output.write(f"{name}={value}\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
