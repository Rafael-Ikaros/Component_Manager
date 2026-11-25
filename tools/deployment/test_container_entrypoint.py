import json
import os
import subprocess
import unittest


API_IMAGE = os.getenv("COMPONENT_VAULT_API_TEST_IMAGE", "component-vault-server:ci")
LEGACY_WEB_IMAGE = os.getenv("COMPONENT_VAULT_LEGACY_WEB_TEST_IMAGE", "component-vault-management:ci")


class ContainerEntrypointTest(unittest.TestCase):
    def run_once(self, image, command):
        container = subprocess.check_output(
            ["docker", "create", "--pull=never", image, *command],
            text=True,
            timeout=30,
        ).strip()
        self.addCleanup(
            subprocess.run,
            ["docker", "rm", "--force", container],
            stdout=subprocess.DEVNULL,
            check=True,
            timeout=30,
        )
        return subprocess.run(
            ["docker", "start", "--attach", container],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=30,
        )

    def test_targets_share_filesystem_layers_and_keep_defaults(self):
        api, legacy_web = json.loads(subprocess.check_output(
            ["docker", "image", "inspect", API_IMAGE, LEGACY_WEB_IMAGE],
            text=True,
            timeout=30,
        ))
        self.assertEqual(api["RootFS"]["Layers"], legacy_web["RootFS"]["Layers"])
        for image, role, ports in (
            (api, "api", {"8787/tcp"}),
            (legacy_web, "web", {"80/tcp", "8790/tcp"}),
        ):
            with self.subTest(role=role):
                config = image["Config"]
                self.assertEqual(config["Entrypoint"], ["/usr/local/bin/componentvault-entrypoint"])
                self.assertEqual(config["Cmd"], [role])
                self.assertEqual(set(config["ExposedPorts"]), ports)
                self.assertEqual(config["Labels"]["io.componentvault.image-layout"], "unified")

    def test_explicit_legacy_commands_keep_their_arguments(self):
        for image, command, expected in (
            (API_IMAGE, ["uvicorn", "--version"], "Running uvicorn"),
            (LEGACY_WEB_IMAGE, ["nginx", "-v"], "nginx version:"),
            (API_IMAGE, ["api", "--version"], "Running uvicorn"),
            (API_IMAGE, ["updater", "--version"], "Running uvicorn"),
        ):
            with self.subTest(command=command):
                result = self.run_once(image, command)
                self.assertEqual(result.returncode, 0, result.stdout)
                self.assertIn(expected, result.stdout)

    def test_help_and_unknown_role_do_not_start_a_service(self):
        help_result = self.run_once(API_IMAGE, ["--help"])
        self.assertEqual(help_result.returncode, 0, help_result.stdout)
        self.assertIn("api|web|updater", help_result.stdout)
        invalid = self.run_once(API_IMAGE, ["not-a-role"])
        self.assertNotEqual(invalid.returncode, 0, invalid.stdout)
        self.assertIn("Unknown container role: not-a-role", invalid.stdout)
        self.assertIn("--help", invalid.stdout)
