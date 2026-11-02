"""Run the actual POSIX helper against an isolated fake Docker CLI."""
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


MOCK_DOCKER = r'''#!/bin/sh
set -eu
case "$1" in
 ps) case "$*" in *service=api*) echo api-id;; *service=admin-web*) [ "${MOCK_WEB:-1}" = 0 ] || echo web-id;; esac;;
 inspect) case "$3" in *working_dir*) echo "$MOCK_DIR";; *config_files*) echo "$MOCK_FILES";; *Config.Image*) echo rafaelikaros/component_manager:latest;; esac;;
 image)
   web=false; case "$5" in *:web-*) web=true;; esac
   case "$4" in
     *server-update*) echo "${MOCK_CAPABILITY:-1}";;
     *image.version*) echo v0.7.9-dev.6;;
     *image.revision*) if [ "$web" = true ]; then echo "${MOCK_WEB_REVISION:-abc}"; else echo abc; fi;;
   esac;;
 compose)
   shift; [ "$1" != version ] || exit 0
   [ -z "${COMPONENT_VAULT_IMAGE+x}" ] && [ -z "${COMPONENT_VAULT_WEB_IMAGE+x}" ] || exit 9
   printf '<%s>\n' "$@" >> "$MOCK_LOG"
   envfile=
   for arg do
     if [ "$arg" = config ]; then
       if [ "${MOCK_HARDCODED:-0}" = 1 ]; then echo rafaelikaros/component_manager:old; exit 0; fi
       case "$*" in
         *admin-web*) sed -n 's/^COMPONENT_VAULT_WEB_IMAGE=//p' "$envfile";;
         *) sed -n 's/^COMPONENT_VAULT_IMAGE=//p' "$envfile";;
       esac
       exit 0
     fi
     if [ "$arg" = --env-file ]; then next_env=true; continue; fi
     if [ "${next_env:-false}" = true ]; then envfile=$arg; next_env=false; fi
     if [ "$arg" = pull ] && [ "${MOCK_PULL_FAIL:-0}" = 1 ]; then exit 4; fi
   done;;
 *) exit 5;;
esac
'''


class HelperTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="updater fixture ")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.deployment = self.root / "NAS deployment with spaces"
        self.deployment.mkdir()
        self.bin = self.root / "mock tools"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text(MOCK_DOCKER, encoding="utf-8", newline="\n")
        docker.chmod(0o755)
        self.envfile = self.deployment / ".env"
        self.original = "# preserve token and comments\nAPI_TOKEN=private-fixture\nCOMPONENT_VAULT_IMAGE=rafaelikaros/component_manager:0.7.4\n"
        self.envfile.write_text(self.original, encoding="utf-8", newline="\n")
        self.files = [self.deployment / "hub compose.yml", self.deployment / "watchtower override.yml"]
        for path in self.files:
            path.write_text("services: {}\n", encoding="utf-8")
        self.log = self.root / "commands.log"
        self.result = self.root / "metadata"
        self.shell = shutil.which("sh") or "C:/Program Files/Git/bin/bash.exe"
        if not Path(self.shell).exists():
            self.skipTest("POSIX shell is unavailable")

    def run_helper(self, extra=None, dry_run=False):
        environment = os.environ | {
            "FIXTURE_BIN": self.bin.as_posix(), "MOCK_DIR": self.deployment.as_posix(),
            "MOCK_FILES": ",".join(path.as_posix() for path in self.files),
            "MOCK_LOG": self.log.as_posix(), "COMPONENT_VAULT_IMAGE": "stale/shell:tag",
            "COMPONENT_VAULT_WEB_IMAGE": "stale/shell:web-tag",
        } | (extra or {})
        prefix = 'PATH="$FIXTURE_BIN:$PATH";'
        if os.name == "nt":
            prefix = 'PATH="$(cygpath -u "$FIXTURE_BIN"):$PATH";'
        script = Path(__file__).resolve().parents[1] / "tools/deployment/switch-channel.sh"
        command = [self.shell, "-c", prefix + ' exec sh "$@"', "fixture", script.as_posix(),
                   "--project-name", "original-project", "--channel", "dev",
                   "--require-web-update", "--result-file", self.result.as_posix()]
        if dry_run:
            command.append("--dry-run")
        result = subprocess.run(command, env=environment, capture_output=True, text=True)
        self.assertNotIn("private-fixture", result.stdout + result.stderr)
        return result

    def assert_unchanged(self):
        self.assertEqual(self.envfile.read_text(), self.original)
        self.assertFalse(list(self.deployment.glob(".env.before-channel-*")))
        self.assertFalse(list(self.deployment.glob(".env.channel.*")))
        self.assertFalse(self.result.exists())
        self.assertNotIn("<up>", self.log.read_text() if self.log.exists() else "")

    def test_supported_pair_switches_and_writes_only_metadata(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.result.read_text().splitlines(), ["v0.7.9-dev.6", "abc", "true"])
        self.assertIn("COMPONENT_VAULT_IMAGE=rafaelikaros/component_manager:dev", self.envfile.read_text())
        self.assertIn(f"<{self.files[1].as_posix()}>", self.log.read_text())
        self.assertIn("<--no-deps>", self.log.read_text())
        self.assertEqual(next(self.deployment.glob(".env.before-channel-*")).read_text(), self.original)

    @unittest.skipUnless(os.name == "posix", "POSIX ownership/mode require a POSIX filesystem")
    def test_env_and_backup_keep_original_owner_group_and_mode(self):
        self.envfile.chmod(0o640)
        if os.geteuid() == 0:
            # Match the sidecar-root/host-user ownership boundary when run as root.
            os.chown(self.envfile, 13579, 13580)
        original = self.envfile.stat()
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        backup = next(self.deployment.glob(".env.before-channel-*"))
        for path in (self.envfile, backup):
            actual = path.stat()
            self.assertEqual((actual.st_uid, actual.st_gid, stat.S_IMODE(actual.st_mode)),
                             (original.st_uid, original.st_gid, stat.S_IMODE(original.st_mode)))
        self.assertEqual(backup.read_text(), self.original)

    def test_failed_metadata_copy_does_not_modify_deployment(self):
        copy = self.bin / "cp"
        copy.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8", newline="\n")
        copy.chmod(0o755)
        result = self.run_helper()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("preserve .env ownership and permissions", result.stderr)
        self.assert_unchanged()
        self.assertFalse(self.log.exists())

    def test_incompatible_target_preserves_running_deployment(self):
        self.result.write_text("stale metadata")
        result = self.run_helper({"MOCK_CAPABILITY": "<no value>"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TARGET_WEB_UPDATE_UNSUPPORTED", result.stderr)
        self.assert_unchanged()

    def test_hardcoded_image_is_detected_before_pull(self):
        result = self.run_helper({"MOCK_HARDCODED": "1"})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("hardcoded", result.stderr)
        self.assertNotIn("<pull>", self.log.read_text())
        self.assert_unchanged()

    def test_failed_pull_and_mismatched_pair_preserve_env(self):
        for environment in ({"MOCK_PULL_FAIL": "1"}, {"MOCK_WEB_REVISION": "other-commit"}):
            with self.subTest(environment=environment):
                self.assertNotEqual(self.run_helper(environment).returncode, 0)
                self.assert_unchanged()

    def test_dry_run_changes_nothing(self):
        self.assertEqual(self.run_helper(dry_run=True).returncode, 0)
        self.assert_unchanged()
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
