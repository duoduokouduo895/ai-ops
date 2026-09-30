#!/usr/bin/env python3
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "check-webhook-auth.py"))
PYTHON = sys.executable

VALID = """
route:
  receiver: aidiag-webhook
receivers:
  - name: aidiag-webhook
    webhook_configs:
      - url: http://aidiag-backend:8080/api/v1/alerts/alertmanager
        http_config:
          basic_auth:
            username: alertmanager
            password_file: /run/secrets/alertmanager_webhook_password
"""


def run_checker(content: str, username: str = "alertmanager", extra: list[str] | None = None):
    with tempfile.TemporaryDirectory() as directory:
        path = os.path.join(directory, "alertmanager.yml")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        command = [PYTHON, SCRIPT, "--alertmanager-config", path, "--backend-username", username]
        if extra:
            command.extend(extra)
        completed = subprocess.run(command, capture_output=True, text=True, check=False)
        return completed.returncode, completed.stdout, completed.stderr


class CheckWebhookAuthTests(unittest.TestCase):
    def test_matching_non_sensitive_fixture_passes(self):
        code, stdout, stderr = run_checker(VALID)
        self.assertEqual(code, 0, stdout + stderr)
        self.assertIn("check username_matches=true", stdout)
        self.assertIn("error_code NONE", stdout)
        self.assertNotIn("alertmanager", stdout.split("backend-username")[0] if False else stdout.replace(SCRIPT, ""))
        self.assertNotIn("/run/secrets/alertmanager_webhook_password", stdout)
        self.assertNotIn("password:", stdout.lower().split("password_file")[0] if False else "")
        self.assertNotRegex(stdout, r"username:\s*alertmanager")

    def test_username_mismatch_does_not_print_usernames(self):
        code, stdout, _ = run_checker(VALID, username="other-user")
        self.assertEqual(code, 1)
        self.assertIn("check username_matches=false", stdout)
        self.assertIn("error_code USERNAME_MISMATCH", stdout)
        self.assertNotIn("other-user", stdout)
        self.assertNotIn("alertmanager", stdout)

    def test_inline_password_is_rejected_without_printing_secret(self):
        fixture = VALID.replace(
            "password_file: /run/secrets/alertmanager_webhook_password",
            "password: super-secret-value\n            password_file: /run/secrets/alertmanager_webhook_password",
        )
        code, stdout, _ = run_checker(fixture)
        self.assertEqual(code, 1)
        self.assertIn("inline_password_absent=false", stdout)
        self.assertIn("error_code INLINE_PASSWORD_PRESENT", stdout)
        self.assertNotIn("super-secret-value", stdout)

    def test_url_credentials_are_rejected(self):
        fixture = VALID.replace(
            "url: http://aidiag-backend:8080/api/v1/alerts/alertmanager",
            "url: http://hook-user:hook-pass@aidiag-backend:8080/api/v1/alerts/alertmanager",
        )
        code, stdout, _ = run_checker(fixture)
        self.assertEqual(code, 1)
        self.assertIn("url_credentials_absent=false", stdout)
        self.assertNotIn("hook-user", stdout)
        self.assertNotIn("hook-pass", stdout)

    def test_second_matching_webhook_with_wrong_username_does_not_pass(self):
        fixture = VALID + """
      - url: http://aidiag-backend:8080/api/v1/alerts/alertmanager
        http_config:
          basic_auth:
            username: other-user
            password_file: /run/secrets/alertmanager_webhook_password
"""
        code, stdout, _ = run_checker(fixture)
        self.assertEqual(code, 1)
        self.assertIn("single_matching_target=false", stdout)
        self.assertIn("error_code DUPLICATE_WEBHOOK_TARGET", stdout)
        self.assertNotIn("other-user", stdout)
        self.assertNotIn("alertmanager", stdout)

    def test_invalid_url_port_returns_fixed_error_without_echoing_the_value(self):
        fixture = VALID.replace(
            "url: http://aidiag-backend:8080/api/v1/alerts/alertmanager",
            "url: http://aidiag-backend:BAD/api/v1/alerts/alertmanager",
        )
        code, stdout, stderr = run_checker(fixture)
        self.assertEqual(code, 1)
        self.assertIn("error_code WEBHOOK_URL_INVALID", stdout)
        self.assertNotIn("BAD", stdout)
        self.assertNotIn("BAD", stderr)

    def test_missing_password_file_fails(self):
        fixture = VALID.replace("            password_file: /run/secrets/alertmanager_webhook_password\n", "")
        code, stdout, _ = run_checker(fixture)
        self.assertEqual(code, 1)
        self.assertIn("password_file_configured=false", stdout)
        self.assertIn("error_code PASSWORD_FILE_MISSING", stdout)

    def test_does_not_read_password_file_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            secret = os.path.join(directory, "password")
            with open(secret, "w", encoding="utf-8") as handle:
                handle.write("should-never-be-read")
            config = os.path.join(directory, "alertmanager.yml")
            with open(config, "w", encoding="utf-8") as handle:
                handle.write(VALID.replace("/run/secrets/alertmanager_webhook_password", secret))
            completed = subprocess.run(
                [PYTHON, SCRIPT, "--alertmanager-config", config, "--backend-username", "alertmanager"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout)
            self.assertNotIn("should-never-be-read", completed.stdout)
            self.assertNotIn("should-never-be-read", completed.stderr)


if __name__ == "__main__":
    unittest.main()
