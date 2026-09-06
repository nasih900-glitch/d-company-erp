#!/usr/bin/env python3
"""Validate the repository's project-local Codex routing configuration."""

from __future__ import annotations

import sys
import tomllib
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CODEX_DIR = ROOT / ".codex"
AGENT_DIR = CODEX_DIR / "agents"

EXPECTED_AGENTS = {
    "erp_explorer": ("gpt-5.6-luna", "low", "read-only"),
    "erp_implementer": ("gpt-5.6-sol", "high", None),
    "erp_verifier": ("gpt-5.6-terra", "high", None),
    "erp_security_reviewer": ("gpt-6-astra", "high", "read-only"),
    "erp_release_auditor": ("gpt-6-astra", "max", "read-only"),
    "erp_incident_architect": ("gpt-6-astra", "ultra", "read-only"),
}
FORBIDDEN_KEYS = {"approval_policy", "approvals_reviewer", "default_permissions"}
REQUIRED_AGENT_KEYS = {
    "name",
    "description",
    "developer_instructions",
    "model",
    "model_reasoning_effort",
}


def load_toml(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as handle:
            return tomllib.load(handle)
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ValueError(f"{path.relative_to(ROOT)}: {exc}") from exc


def walk_keys(value: Any, prefix: tuple[str, ...] = ()) -> list[tuple[str, ...]]:
    if not isinstance(value, dict):
        return []
    paths: list[tuple[str, ...]] = []
    for key, child in value.items():
        path = (*prefix, key)
        paths.append(path)
        paths.extend(walk_keys(child, path))
    return paths


def contains_value(value: Any, forbidden: str) -> bool:
    if isinstance(value, dict):
        return any(contains_value(child, forbidden) for child in value.values())
    if isinstance(value, list):
        return any(contains_value(child, forbidden) for child in value)
    return value == forbidden


def validate() -> list[str]:
    errors: list[str] = []
    config_path = CODEX_DIR / "config.toml"
    try:
        config = load_toml(config_path)
    except ValueError as exc:
        return [str(exc)]

    if set(config) != {"agents"}:
        errors.append(".codex/config.toml may contain only the [agents] table")
    agents_config = config.get("agents", {})
    expected_global = {
        "enabled": True,
        "max_concurrent_threads_per_session": 4,
        "interrupt_message": True,
    }
    if agents_config != expected_global:
        errors.append(".codex/config.toml [agents] settings do not match the reviewed policy")

    found: dict[str, Path] = {}
    for path in sorted(AGENT_DIR.glob("*.toml")):
        try:
            agent = load_toml(path)
        except ValueError as exc:
            errors.append(str(exc))
            continue

        missing = REQUIRED_AGENT_KEYS - set(agent)
        if missing:
            errors.append(f"{path.relative_to(ROOT)} missing keys: {', '.join(sorted(missing))}")
            continue

        name = agent["name"]
        if not isinstance(name, str) or name not in EXPECTED_AGENTS:
            errors.append(f"{path.relative_to(ROOT)} has an unexpected agent name: {name!r}")
            continue
        if name in found:
            errors.append(f"duplicate agent name {name!r} in {found[name].name} and {path.name}")
        found[name] = path

        model, effort, sandbox = EXPECTED_AGENTS[name]
        if agent.get("model") != model or agent.get("model_reasoning_effort") != effort:
            errors.append(f"{path.relative_to(ROOT)} has an unreviewed model or reasoning effort")
        if sandbox is None:
            if "sandbox_mode" in agent:
                errors.append(f"{path.relative_to(ROOT)} must inherit the active permission mode")
        elif agent.get("sandbox_mode") != sandbox:
            errors.append(f"{path.relative_to(ROOT)} must use sandbox_mode = {sandbox!r}")

        for key_path in walk_keys(agent):
            if key_path[-1] in FORBIDDEN_KEYS:
                errors.append(f"{path.relative_to(ROOT)} contains forbidden key {'.'.join(key_path)}")
        if contains_value(agent, "danger-full-access"):
            errors.append(f"{path.relative_to(ROOT)} enables danger-full-access")

    missing_agents = set(EXPECTED_AGENTS) - set(found)
    if missing_agents:
        errors.append(f"missing agent definitions: {', '.join(sorted(missing_agents))}")

    agents_guide = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    required_phrases = (
        "## Codex task routing and verification phases",
        "Never bypass approvals or sandboxing automatically.",
        "must not claim that its own model switched dynamically.",
        "Use the lowest-cost role that can answer the bounded question.",
    )
    for phrase in required_phrases:
        if phrase not in agents_guide:
            errors.append(f"AGENTS.md missing required routing policy: {phrase}")

    return errors


def main() -> int:
    errors = validate()
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Codex routing validation passed: {len(EXPECTED_AGENTS)} agents, safe permission policy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
