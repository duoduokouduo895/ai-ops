#!/usr/bin/env python3
"""Read-only Alertmanager webhook authentication contract check.

Compares a caller-supplied backend username with every webhook on a named
receiver that targets the expected backend URL. Never prints usernames or
passwords and never reads password_file contents. A passing check proves
configuration shape only, not that the password matches or that a live
webhook delivery succeeded.
"""

from __future__ import annotations

import argparse
import sys
from urllib.parse import urlparse, urlunparse

EXIT_OK = 0
EXIT_FAIL = 1
EXIT_USAGE = 2
EXIT_DEPENDENCY = 3

DEFAULT_RECEIVER = "aidiag-webhook"
DEFAULT_URL = "http://aidiag-backend:8080/api/v1/alerts/alertmanager"

CHECK_ORDER = (
    "receiver_present",
    "webhook_config_present",
    "webhook_url_matches",
    "single_matching_target",
    "username_matches",
    "password_file_configured",
    "inline_password_absent",
    "url_credentials_absent",
)

ERROR_CODES = {
    "receiver_present": "RECEIVER_MISSING",
    "webhook_config_present": "WEBHOOK_CONFIG_MISSING",
    "webhook_url_matches": "WEBHOOK_URL_MISMATCH",
    "single_matching_target": "DUPLICATE_WEBHOOK_TARGET",
    "username_matches": "USERNAME_MISMATCH",
    "password_file_configured": "PASSWORD_FILE_MISSING",
    "inline_password_absent": "INLINE_PASSWORD_PRESENT",
    "url_credentials_absent": "URL_CREDENTIALS_PRESENT",
}


class WebhookUrlInvalid(Exception):
    """Raised when a webhook URL cannot be parsed without echoing the value."""


def load_yaml():
    try:
        import yaml  # type: ignore
    except ImportError:
        print("error_code PYYAML_MISSING")
        print("detail PyYAML is required; the checker does not install packages")
        sys.exit(EXIT_DEPENDENCY)
    return yaml


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check Alertmanager webhook auth configuration without reading secrets")
    parser.add_argument("--alertmanager-config", required=True, help="Path to the Alertmanager YAML file")
    parser.add_argument("--backend-username", required=True, help="Expected Basic username; never printed")
    parser.add_argument("--receiver", default=DEFAULT_RECEIVER)
    parser.add_argument("--webhook-url", default=DEFAULT_URL)
    return parser.parse_args(argv)


def webhook_configs(receiver: dict | None) -> list[dict]:
    if not isinstance(receiver, dict):
        return []
    configs = receiver.get("webhook_configs")
    if not isinstance(configs, list):
        return []
    return [item for item in configs if isinstance(item, dict)]


def canonical_target(url: str) -> str:
    try:
        parsed = urlparse(url or "")
        host = parsed.hostname or ""
        path = parsed.path or ""
        scheme = parsed.scheme or ""
        port = parsed.port
        netloc = host if port is None else f"{host}:{port}"
        return urlunparse((scheme, netloc, path, "", "", ""))
    except ValueError as exc:
        raise WebhookUrlInvalid from exc


def has_url_credentials(url: str) -> bool:
    if not url:
        return False
    parsed = urlparse(url)
    if parsed.username or parsed.password:
        return True
    return "://" in url and "@" in url.split("://", 1)[1].split("/", 1)[0]


def matching_webhooks(configs: list[dict], expected_url: str) -> list[dict]:
    expected = canonical_target(expected_url)
    matched = []
    for config in configs:
        url = config.get("url", "")
        if isinstance(url, str) and canonical_target(url) == expected:
            matched.append(config)
    return matched


def auth_block(webhook: dict | None) -> dict:
    if not isinstance(webhook, dict):
        return {}
    http_config = webhook.get("http_config")
    if isinstance(http_config, dict) and isinstance(http_config.get("basic_auth"), dict):
        return http_config["basic_auth"]
    return {}


def evaluate(document: dict, backend_username: str, receiver_name: str, expected_url: str) -> dict[str, bool]:
    receivers = document.get("receivers") if isinstance(document, dict) else None
    receiver = None
    if isinstance(receivers, list):
        for item in receivers:
            if isinstance(item, dict) and item.get("name") == receiver_name:
                receiver = item
                break
    configs = webhook_configs(receiver)
    matched = matching_webhooks(configs, expected_url)
    webhook = matched[0] if len(matched) == 1 else None
    auth = auth_block(webhook)
    url = webhook.get("url", "") if isinstance(webhook, dict) else ""
    username = auth.get("username")
    password_file = auth.get("password_file")
    inline_password = auth.get("password")
    usernames_ok = all(
        isinstance(auth_block(item).get("username"), str) and auth_block(item).get("username") == backend_username
        for item in matched
    ) if matched else False
    password_files_ok = all(
        isinstance(auth_block(item).get("password_file"), str) and bool(str(auth_block(item).get("password_file")).strip())
        for item in matched
    ) if matched else False
    inline_absent = all(auth_block(item).get("password") is None for item in matched) if matched else False
    credentials_absent = all(
        isinstance(item.get("url"), str) and not has_url_credentials(item.get("url", ""))
        for item in matched
    ) if matched else False
    return {
        "receiver_present": receiver is not None,
        "webhook_config_present": bool(configs),
        "webhook_url_matches": bool(matched),
        "single_matching_target": len(matched) == 1,
        "username_matches": usernames_ok and len(matched) == 1,
        "password_file_configured": password_files_ok and len(matched) == 1,
        "inline_password_absent": inline_absent and len(matched) == 1,
        "url_credentials_absent": credentials_absent and len(matched) == 1,
    }


def first_error(results: dict[str, bool]) -> str:
    for name in CHECK_ORDER:
        if not results.get(name, False):
            return ERROR_CODES[name]
    return "NONE"


def emit(results: dict[str, bool]) -> int:
    for name in CHECK_ORDER:
        print(f"check {name}={str(results.get(name, False)).lower()}")
    error = first_error(results)
    print(f"error_code {error}")
    print("note passing this check does not prove password equality or live webhook delivery")
    print("note other receivers were not checked")
    return EXIT_OK if error == "NONE" else EXIT_FAIL


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    yaml = load_yaml()
    try:
        with open(args.alertmanager_config, "r", encoding="utf-8") as handle:
            document = yaml.safe_load(handle)
    except FileNotFoundError:
        print("error_code CONFIG_MISSING")
        return EXIT_FAIL
    except Exception:
        print("error_code YAML_PARSE_FAILED")
        return EXIT_FAIL
    if not isinstance(document, dict):
        print("error_code YAML_PARSE_FAILED")
        return EXIT_FAIL
    try:
        return emit(evaluate(document, args.backend_username, args.receiver, args.webhook_url))
    except WebhookUrlInvalid:
        print("error_code WEBHOOK_URL_INVALID")
        print("note passing this check does not prove password equality or live webhook delivery")
        return EXIT_FAIL


if __name__ == "__main__":
    sys.exit(main())
