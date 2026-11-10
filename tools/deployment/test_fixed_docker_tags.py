import io
from contextlib import redirect_stdout
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

import fixed_docker_tags as tags


REPOSITORY = "example/component_manager"
VERSION = "v0.7.9-dev.17"
REVISION = "0" * 40
DIGEST = "sha256:" + "a" * 64


class FixedTagTest(unittest.TestCase):
    def test_existing_tag_returns_registry_digest(self):
        with patch.object(tags, "urlopen") as request:
            response = request.return_value.__enter__.return_value
            response.status = 200
            response.headers = {"Docker-Content-Digest": DIGEST}
            self.assertEqual(tags.tag_digest(REPOSITORY, VERSION[1:], "token"), DIGEST)
            self.assertEqual(request.call_args.args[0].get_header("Authorization"), "Bearer token")

    def test_only_registry_manifest_missing_allows_publish(self):
        for status, code, message, missing in (
            (404, "MANIFEST_UNKNOWN", "manifest unknown", True),
            (404, "MANIFEST_UNKNOWN", "OCI manifest found, but accept header does not support OCI manifests", False),
            (404, "NAME_UNKNOWN", "repository name not known to registry", False),
            (401, "UNAUTHORIZED", "authentication required", False),
            (429, "TOOMANYREQUESTS", "too many requests", False),
            (503, "UNAVAILABLE", "service unavailable", False),
        ):
            with self.subTest(status=status, message=message):
                body = json.dumps({"errors": [{"code": code, "message": message}]}).encode()
                error = HTTPError("https://registry-1.docker.io/", status, message, {}, io.BytesIO(body))
                with patch.object(tags, "urlopen", side_effect=error):
                    if missing:
                        self.assertIsNone(tags.tag_digest(REPOSITORY, VERSION[1:], "token"))
                    else:
                        with self.assertRaisesRegex(RuntimeError, f"HTTP {status}"):
                            tags.tag_digest(REPOSITORY, VERSION[1:], "token")

    def test_transport_error_or_invalid_digest_stops_publish(self):
        with patch.object(tags, "urlopen", side_effect=URLError("timeout")):
            with self.assertRaises(URLError):
                tags.tag_digest(REPOSITORY, VERSION[1:], "token")
        with patch.object(tags, "urlopen") as request:
            response = request.return_value.__enter__.return_value
            response.status = 200
            response.headers = {}
            with self.assertRaisesRegex(RuntimeError, "valid digest"):
                tags.tag_digest(REPOSITORY, VERSION[1:], "token")

    def test_both_platforms_must_match_release_identity(self):
        config = {
            f"linux/{arch}": {"os": "linux", "architecture": arch, "config": {"Labels": {
                "org.opencontainers.image.version": VERSION,
                "org.opencontainers.image.revision": REVISION,
            }}}
            for arch in ("amd64", "arm64")
        }
        with patch.object(tags.subprocess, "check_output", return_value=json.dumps(config)) as inspect:
            tags.verify_image(REPOSITORY, VERSION[1:], DIGEST, VERSION, REVISION)
            self.assertIn(f"{REPOSITORY}@{DIGEST}", inspect.call_args.args[0])
            config["linux/arm64"]["config"]["Labels"]["org.opencontainers.image.revision"] = "wrong"
            inspect.return_value = json.dumps(config)
            with self.assertRaisesRegex(RuntimeError, "arm64.*release identity"):
                tags.verify_image(REPOSITORY, VERSION[1:], DIGEST, VERSION, REVISION)
            del config["linux/arm64"]
            inspect.return_value = json.dumps(config)
            with self.assertRaisesRegex(RuntimeError, "must include"):
                tags.verify_image(REPOSITORY, VERSION[1:], DIGEST, VERSION, REVISION)

    def test_preflight_outputs_reuse_and_missing_per_image(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            environment = {"DOCKERHUB_IMAGE": REPOSITORY, "DOCKERHUB_USERNAME": "user", "DOCKERHUB_TOKEN": "secret",
                           "RELEASE_TAG": VERSION, "EXPECTED_REVISION": REVISION, "GITHUB_OUTPUT": str(output)}
            with (patch.dict(os.environ, environment), patch("sys.argv", ["fixed_docker_tags.py"]),
                  patch.object(tags, "registry_token", return_value="token"),
                  patch.object(tags, "tag_digest", side_effect=[DIGEST, None]),
                  patch.object(tags, "verify_image") as verify, redirect_stdout(io.StringIO())):
                tags.main()
            verify.assert_called_once_with(REPOSITORY, VERSION[1:], DIGEST, VERSION, REVISION)
            result = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual((result["api_missing"], result["web_missing"]), ("false", "true"))
            self.assertEqual((result["api_digest"], result["web_digest"]), (DIGEST, ""))
            self.assertEqual((result["api_channel"], result["web_channel"]), (f"{REPOSITORY}:dev", f"{REPOSITORY}:web-dev"))

    def test_post_publish_rejects_a_tag_that_moved(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            environment = {"DOCKERHUB_IMAGE": REPOSITORY, "DOCKERHUB_USERNAME": "user", "DOCKERHUB_TOKEN": "secret",
                           "RELEASE_TAG": "v0.7.9", "EXPECTED_REVISION": REVISION, "GITHUB_OUTPUT": str(output)}
            with (patch.dict(os.environ, environment),
                  patch("sys.argv", ["fixed_docker_tags.py", "--expect-digests", DIGEST, DIGEST]),
                  patch.object(tags, "registry_token", return_value="token"),
                  patch.object(tags, "tag_digest", return_value="sha256:" + "b" * 64)):
                with self.assertRaisesRegex(RuntimeError, "expected digest"):
                    tags.main()
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
