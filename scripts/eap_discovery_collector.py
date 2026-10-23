#!/usr/bin/env python3
"""Small, dependency-free EAP endpoint discovery collector.

The collector only reads process metadata and bounded configuration/manifest
files. It never executes commands declared by MCP configuration files and it
never sends raw file contents or credential values to EAP.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


VERSION = "0.3.0"
EXCLUDED_DIRECTORIES = {
    ".git", "node_modules", "target", "dist", "build", ".idea", ".venv", "venv", "__pycache__"
}
SIGNATURES = [
    ("HelloBoss", "深圳矩维智能", ("boss-agent", "helloboss"), ()),
    ("Codex", "OpenAI", ("codex", "@openai/codex"), ("@openai/codex",)),
    ("Claude Code", "Anthropic", ("claude", "@anthropic-ai/claude-code"), ("@anthropic-ai/claude-code",)),
    ("Cursor", "Cursor", ("cursor",), ()),
    ("Windsurf", "Codeium", ("windsurf",), (".windsurf/mcp.json", "codeium.windsurf")),
    ("Kiro", "Amazon Web Services", ("kiro",), (".kiro/settings/mcp.json", "kiro-agent")),
    ("OpenCode", "Anomaly", ("opencode",), ("opencode.json", "opencode-ai")),
    ("Amp", "Sourcegraph", ("@sourcegraph/amp",), ("@sourcegraph/amp",)),
    ("Amazon Q Developer", "Amazon Web Services", ("amazon q", "amazonq", "aws q"), ("aws.amazonq", "amazon q developer")),
    ("Antigravity", "Google", ("antigravity",), (".antigravity/", "antigravity.agent")),
    ("OpenClaw", "OpenClaw", ("openclaw",), (".openclaw/", "openclaw.json")),
    ("Cline", "Cline", ("saoudrizwan.claude-dev", "cline-agent"), ("saoudrizwan.claude-dev", "cline_mcp_settings")),
    ("Roo Code", "Roo Veterinary", ("rooveterinaryinc.roo-cline", "roo-code"), ("rooveterinaryinc.roo-cline", "roo-cline")),
    ("Agent Skills", "Open Agent Standard", (), ()),
    ("Gemini CLI", "Google", ("gemini", "@google/gemini-cli"), ("@google/gemini-cli",)),
    ("GitHub Copilot", "GitHub", ("copilot-agent", "github.copilot"), ("@github/copilot",)),
    ("Mastra", "Mastra", ("mastra",), ("@mastra/core",)),
    ("LangGraph", "LangChain", ("langgraph",), ("langgraph", "langchain")),
    ("CrewAI", "CrewAI", ("crewai",), ("crewai",)),
    ("AutoGen", "Microsoft", ("autogen",), ("pyautogen", "autogen-agentchat")),
    ("Semantic Kernel", "Microsoft", ("semantic-kernel",), ("semantic-kernel", "microsoft.semantickernel")),
    ("OpenAI Agents SDK", "OpenAI", ("openai-agents",), ("openai-agents",)),
]
DOCKER_AGENT_LABELS = (
    "eap.agent", "eap.discovery.agent", "com.eap.agent", "ai.agent",
)
DOCKER_PRODUCT_LABELS = ("eap.agent.product", "com.eap.agent.product", "ai.agent.product")
DOCKER_VENDOR_LABELS = ("eap.agent.vendor", "com.eap.agent.vendor", "ai.agent.vendor")
DOCKER_INFRA_SERVICES = {
    "frontend", "nginx", "proxy", "gateway", "mysql", "mariadb", "postgres", "postgresql",
    "database", "db", "redis", "kafka", "rabbitmq", "chroma", "qdrant", "milvus",
    "elasticsearch", "opensearch", "prometheus", "grafana",
}
DOCKER_AGENT_MARKERS = (
    "agent", "mcp", "langgraph", "crewai", "autogen", "semantic-kernel",
    "openai-agents", "dify", "flowise", "langflow", "coze", "fastgpt", "maxkb",
)
DOCKER_AI_ENV_KEYS = {
    "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "AZURE_OPENAI_API_KEY", "GOOGLE_API_KEY",
    "GEMINI_API_KEY", "DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY", "MOONSHOT_API_KEY",
    "VOLCENGINE_API_KEY", "ARK_API_KEY", "OLLAMA_HOST", "LITELLM_MASTER_KEY",
}
AUTO_APPROVE_MARKERS = (
    "--dangerously-skip-permissions", "--yolo", "--full-auto",
    "approval_policy=never", "approval-mode=full-auto",
)
CREDENTIAL_MARKERS = (
    "openai_api_key", "anthropic_api_key", "google_api_key",
    "azure_openai_api_key", "apikey", "api_key", "token", "secret",
)
ELIGIBLE_NAMES = {
    "package.json", "pyproject.toml", "requirements.txt", "pom.xml",
    "mcp.json", ".mcp.json", "claude_desktop_config.json",
}
SKILL_NAME = "skill.md"
PLUGIN_NAME = "plugin.json"
SKILL_DIRECTORIES = {".codex", ".claude", ".cursor", ".windsurf", ".kiro", ".gemini", ".agents", "skills"}
PLUGIN_DIRECTORIES = {".codex-plugin", ".claude-plugin"}
NETWORK_ACCESS_MARKERS = ("curl ", "wget ", "scp ", "ssh ", "invoke-webrequest", "start-bitstransfer")
PRIVILEGED_COMMAND_MARKERS = ("sudo ", "rm -rf", "chmod 777", "powershell -encodedcommand", "invoke-expression", "--dangerously-skip-permissions")
PROMPT_INJECTION_MARKERS = ("ignore previous instructions", "ignore all previous", "reveal the system prompt", "jailbreak", "<system>")
TOOL_POISONING_MARKERS = ("tool poisoning", "do not tell the user", "silently upload", "send this to", "hidden instruction", "invisible instruction")
TOOL_SHADOWING_MARKERS = ("shadow tool", "tool shadowing", "impersonate", "override existing tool", "duplicate tool")
MALWARE_MARKERS = ("base64 -d", "certutil -decode", "download and execute", "powershell -enc", "chmod +x", "/dev/tcp/")


def digest(*values: Any) -> str:
    joined = "|".join("" if value is None else str(value) for value in values)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def host_name() -> str:
    return socket.gethostname() or os.environ.get("HOSTNAME", "unknown-host")


def account_name() -> str:
    return os.environ.get("USER") or os.environ.get("USERNAME") or "unknown"


def endpoint_key() -> str:
    configured = os.environ.get("EAP_DISCOVERY_ENDPOINT_KEY", "").strip()
    if configured:
        return configured[:128]
    return "endpoint-" + digest(host_name(), account_name(), platform.system())[:24]


def configured_roots() -> list[Path]:
    roots: list[Path] = [Path.cwd()]
    configured = os.environ.get("EAP_DISCOVERY_SCAN_ROOTS", "")
    roots.extend(Path(value).expanduser() for value in configured.split(",") if value.strip())
    if os.environ.get("EAP_DISCOVERY_INCLUDE_KNOWN_USER_LOCATIONS", "true").lower() != "false":
        home = Path.home()
        roots.extend(home / relative for relative in (
            ".codex", ".claude", ".cursor", ".gemini", ".windsurf", ".kiro", ".opencode", ".amp", ".antigravity", ".openclaw", ".agents",
            ".config/Claude", ".config/Cursor", ".config/Code/User", ".config/opencode", ".config/agents", ".aws/amazonq",
            "Library/Application Support/Claude",
            "Library/Application Support/Cursor/User",
            "Library/Application Support/Code/User",
            "Library/Application Support/Windsurf/User",
            "Library/Application Support/Kiro/User",
        ))
        appdata = os.environ.get("APPDATA", "").strip()
        if appdata:
            roots.extend(Path(appdata) / relative for relative in ("Claude", "Cursor/User", "Code/User", "Windsurf/User", "Kiro/User"))
    unique: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        normalized = str(root.expanduser().absolute())
        if normalized not in seen:
            seen.add(normalized)
            unique.append(Path(normalized))
    return unique


def process_rows() -> list[tuple[str, str, str]]:
    if platform.system().lower() == "windows":
        command = (
            "Get-CimInstance Win32_Process | Select-Object ProcessId,Name,ExecutablePath,CommandLine "
            "| ConvertTo-Json -Compress"
        )
        for executable in ("powershell.exe", "pwsh"):
            try:
                result = subprocess.run(
                    [executable, "-NoProfile", "-NonInteractive", "-Command", command],
                    check=False,
                    capture_output=True,
                    text=True,
                    timeout=8,
                )
            except (OSError, subprocess.SubprocessError):
                continue
            try:
                document = json.loads(result.stdout or "[]")
            except (ValueError, TypeError):
                continue
            if isinstance(document, dict):
                document = [document]
            rows: list[tuple[str, str, str]] = []
            for item in document if isinstance(document, list) else []:
                if not isinstance(item, dict):
                    continue
                pid = str(item.get("ProcessId", ""))
                command_path = str(item.get("ExecutablePath") or item.get("Name") or "")
                args = str(item.get("CommandLine") or "")
                if pid and command_path:
                    rows.append((pid, command_path, args))
            return rows
        return []
    try:
        result = subprocess.run(
            ["ps", "-axo", "pid=,comm=,args="],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    rows: list[tuple[str, str, str]] = []
    for line in result.stdout.splitlines():
        match = re.match(r"^\s*(\d+)\s+(\S+)\s*(.*)$", line)
        if match:
            rows.append((match.group(1), match.group(2), match.group(3)))
    return rows


def docker_documents() -> list[dict[str, Any]]:
    if os.environ.get("EAP_DISCOVERY_DOCKER_ENABLED", "true").lower() == "false":
        return []
    try:
        listed = subprocess.run(
            ["docker", "ps", "--format", "{{json .}}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=8,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    container_ids: list[str] = []
    for line in listed.stdout.splitlines():
        try:
            row = json.loads(line)
        except (ValueError, TypeError):
            continue
        container_id = str(row.get("ID") or "").strip()
        if container_id:
            container_ids.append(container_id)
    if not container_ids:
        return []
    try:
        inspected = subprocess.run(
            ["docker", "inspect", *container_ids[:500]],
            check=False,
            capture_output=True,
            text=True,
            timeout=15,
        )
        payload = json.loads(inspected.stdout or "[]")
    except (OSError, subprocess.SubprocessError, ValueError, TypeError):
        return []
    return [item for item in payload if isinstance(item, dict)] if isinstance(payload, list) else []


def value_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value if item is not None]
    if value is None:
        return []
    return [str(value)]


def first_label(labels: dict[str, Any], keys: tuple[str, ...]) -> str | None:
    for key in keys:
        value = labels.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def truthy_label(labels: dict[str, Any]) -> bool:
    value = first_label(labels, DOCKER_AGENT_LABELS)
    return value is not None and value.lower() in {"1", "true", "yes", "on", "enabled"}


def display_product(value: str) -> str:
    words = re.sub(r"[^a-zA-Z0-9]+", " ", value).strip().split()
    return " ".join(word.upper() if len(word) <= 3 else word.capitalize() for word in words)[:128] or "Docker Agent"


def image_version(image: str, labels: dict[str, Any]) -> str | None:
    declared = labels.get("org.opencontainers.image.version")
    if declared is not None and str(declared).strip():
        return str(declared).strip()[:64]
    last_segment = image.rsplit("/", 1)[-1]
    if ":" in last_segment:
        return last_segment.rsplit(":", 1)[-1][:64]
    return None


def docker_ports(document: dict[str, Any]) -> list[str]:
    ports = document.get("NetworkSettings", {}).get("Ports", {}) or {}
    result: list[str] = []
    for container_port, bindings in ports.items():
        if isinstance(bindings, list) and bindings:
            for binding in bindings[:4]:
                if isinstance(binding, dict):
                    result.append(f"{container_port}->{binding.get('HostIp', '')}:{binding.get('HostPort', '')}")
        else:
            result.append(str(container_port))
        if len(result) >= 16:
            break
    return result


def docker_observations() -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    current_host = host_name()
    current_account = account_name()
    for document in docker_documents():
        config = document.get("Config", {}) or {}
        labels = config.get("Labels", {}) or {}
        if not isinstance(labels, dict):
            labels = {}
        host_config = document.get("HostConfig", {}) or {}
        name = str(document.get("Name") or "").lstrip("/")
        image = str(config.get("Image") or "")
        compose_project = str(labels.get("com.docker.compose.project") or "")
        compose_service = str(labels.get("com.docker.compose.service") or "")
        if compose_project.lower() == "eap" or name.lower().startswith("eap-"):
            continue
        explicit_agent = truthy_label(labels)
        if compose_service.lower() in DOCKER_INFRA_SERVICES and not explicit_agent:
            continue
        command_parts = value_list(config.get("Entrypoint")) + value_list(config.get("Cmd"))
        command = " ".join(command_parts)[:1024]
        searchable = " ".join((name, image, compose_project, compose_service, command, " ".join(labels.keys()))).lower()
        signature = signature_for(searchable)
        env_keys = {
            str(item).split("=", 1)[0]
            for item in value_list(config.get("Env"))
            if "=" in str(item)
        }
        ai_env_keys = sorted(env_keys.intersection(DOCKER_AI_ENV_KEYS))
        marker_detected = any(marker in searchable for marker in DOCKER_AGENT_MARKERS)
        backend_role = compose_service.lower() in {"backend", "api", "agent", "runtime", "worker", "server", "app"}
        if not explicit_agent and signature is None and not marker_detected and not (ai_env_keys and backend_role):
            continue
        if signature is not None:
            product, vendor, _, _ = signature
        else:
            product = first_label(labels, DOCKER_PRODUCT_LABELS) or display_product(compose_project or name or image)
            vendor = first_label(labels, DOCKER_VENDOR_LABELS) or "Unknown"
        privileged = bool(host_config.get("Privileged"))
        pid_mode = str(host_config.get("PidMode") or "")
        network_mode = str(host_config.get("NetworkMode") or "")
        mounts = document.get("Mounts", []) or []
        docker_socket_mounted = any(
            isinstance(mount, dict) and str(mount.get("Destination") or "") == "/var/run/docker.sock"
            for mount in mounts
        )
        auto_approve = any(marker in command.lower() for marker in AUTO_APPROVE_MARKERS)
        elevated = privileged or pid_mode == "host" or network_mode == "host" or docker_socket_mounted
        risk = "HIGH" if elevated or auto_approve else "MEDIUM"
        confidence = 98 if explicit_agent else 94 if signature is not None else 88 if ai_env_keys else 82
        networks = sorted((document.get("NetworkSettings", {}).get("Networks", {}) or {}).keys())[:16]
        stable_identity = compose_service or name or image
        observations.append({
            "fingerprint": digest("docker", product, current_host, compose_project, stable_identity),
            "name": f"{product} @ {current_host}",
            "vendor": vendor,
            "product": product,
            "version": image_version(image, labels),
            "assetType": "AGENT",
            "sourceType": "DOCKER",
            "sourceLocation": f"docker://{current_host}/{name}",
            "accountName": current_account,
            "processName": name[:256],
            "executablePath": image[:1024] or None,
            "autoApprove": auto_approve,
            "trustedProcess": None,
            "mcpServerCount": 0,
            "confidence": confidence,
            "riskLevel": risk,
            "evidenceType": "DOCKER_AGENT_WORKLOAD",
            "evidence": {
                "containerId": str(document.get("Id") or "")[:12],
                "image": image[:256],
                "composeProject": compose_project[:128],
                "composeService": compose_service[:128],
                "command": command[:512],
                "state": str(document.get("State", {}).get("Status") or "")[:64],
                "networks": networks,
                "ports": docker_ports(document),
                "explicitAgentLabel": explicit_agent,
                "matchedSignature": product if signature is not None else None,
                "llmCredentialEnvKeys": ai_env_keys,
                "privileged": privileged,
                "hostPidMode": pid_mode == "host",
                "hostNetworkMode": network_mode == "host",
                "dockerSocketMounted": docker_socket_mounted,
            },
        })
    return observations


def signature_for(value: str) -> tuple[str, str, tuple[str, ...], tuple[str, ...]] | None:
    normalized = value.lower()
    for product, vendor, process_tokens, package_names in SIGNATURES:
        if any(token.lower() in normalized for token in process_tokens):
            return product, vendor, process_tokens, package_names
    return None


def signature_for_path(path: Path) -> tuple[str, str, tuple[str, ...], tuple[str, ...]] | None:
    normalized = str(path).replace("\\", "/").lower()
    mappings = (
        ("/.codex/", "Codex"), ("/.claude/", "Claude Code"), ("/.cursor/", "Cursor"),
        ("/.windsurf/", "Windsurf"), ("/.kiro/", "Kiro"), ("/.opencode/", "OpenCode"),
        ("/.gemini/", "Gemini CLI"), ("/.antigravity/", "Antigravity"), ("/.openclaw/", "OpenClaw"),
        ("/.amp/", "Amp"), ("/.aws/amazonq/", "Amazon Q Developer"),
        ("/.agents/", "Agent Skills"), ("/.config/agents/", "Agent Skills"),
        ("/saoudrizwan.claude-dev/", "Cline"), ("/rooveterinaryinc.roo-cline/", "Roo Code"),
    )
    for marker, product in mappings:
        if marker in normalized:
            return next((signature for signature in SIGNATURES if signature[0] == product), None)
    if "/.codex-plugin/" in normalized:
        return next((signature for signature in SIGNATURES if signature[0] == "Codex"), None)
    if "/.claude-plugin/" in normalized:
        return next((signature for signature in SIGNATURES if signature[0] == "Claude Code"), None)
    return None


def is_skill_manifest(path: Path) -> bool:
    normalized = str(path).replace("\\", "/").lower()
    return path.name.lower() == SKILL_NAME and "/skills/" in normalized


def is_plugin_manifest(path: Path) -> bool:
    normalized = str(path).replace("\\", "/").lower()
    return path.name.lower() == PLUGIN_NAME and ("/.codex-plugin/" in normalized or "/.claude-plugin/" in normalized)


def extension_name(path: Path, content: str, plugin: bool) -> tuple[str, str | None]:
    if plugin:
        try:
            document = json.loads(content)
            name = document.get("name") if isinstance(document, dict) else None
            version = document.get("version") if isinstance(document, dict) else None
            return str(name)[:128] if name else (path.parent.parent.name or "Unnamed plugin"), str(version)[:64] if version else None
        except (ValueError, TypeError):
            return path.parent.parent.name or "Unnamed plugin", None
    frontmatter = False
    for line in content.splitlines()[:80]:
        trimmed = line.strip()
        if trimmed == "---":
            if frontmatter:
                break
            frontmatter = True
            continue
        if frontmatter and trimmed.lower().startswith("name:"):
            value = trimmed.split(":", 1)[1].strip().strip("\"'")
            if value:
                return value[:128], None
    return path.parent.name or "Unnamed skill", None


def security_signals(normalized_content: str) -> dict[str, bool]:
    contains = lambda markers: any(marker in normalized_content for marker in markers)
    return {
        "promptInjectionDetected": contains(PROMPT_INJECTION_MARKERS),
        "toolPoisoningDetected": contains(TOOL_POISONING_MARKERS),
        "toolShadowingDetected": contains(TOOL_SHADOWING_MARKERS),
        "malwarePayloadDetected": contains(MALWARE_MARKERS),
        "networkAccessDetected": contains(NETWORK_ACCESS_MARKERS),
    }


def risk_level(auto_approve: bool, trusted: bool | None, credential_reference: bool, mcp_count: int, source: str, security_finding: bool = False) -> str:
    if auto_approve and trusted is False:
        return "CRITICAL"
    if auto_approve or trusted is False or credential_reference or security_finding:
        return "HIGH"
    if source == "PROCESS" or mcp_count > 0:
        return "MEDIUM"
    return "LOW"


def extension_risk_level(auto_approve: bool, credential_reference: bool, network_access: bool, privileged_command: bool, signals: dict[str, bool]) -> str:
    if auto_approve and (privileged_command or signals["malwarePayloadDetected"]):
        return "CRITICAL"
    if auto_approve or credential_reference or privileged_command or any(signals[key] for key in ("promptInjectionDetected", "toolPoisoningDetected", "toolShadowingDetected", "malwarePayloadDetected")):
        return "HIGH"
    if network_access:
        return "MEDIUM"
    return "LOW"


def process_trust(command: str) -> bool | None:
    normalized = (command or "").replace("\\", "/").lower()
    if not normalized:
        return None
    if any(marker in normalized for marker in ("/tmp/", "/downloads/", "\\temp\\", "./")):
        return False
    if normalized.startswith(("/applications/", "/system/", "/usr/", "/opt/", "/library/")) or "program files" in normalized:
        return True
    return None


def process_observations() -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    limit = int(os.environ.get("EAP_DISCOVERY_MAX_PROCESSES", "2000"))
    current_host = host_name()
    current_account = account_name()
    for pid, command, args in process_rows()[:limit]:
        searchable = f"{command} {args}".lower()
        signature = signature_for(searchable)
        if not signature:
            continue
        product, vendor, _, _ = signature
        if product == "Cursor" and any(marker in searchable for marker in ("cursoruiviewservice", "textinput", "helper")):
            continue
        auto_approve = any(marker in searchable for marker in AUTO_APPROVE_MARKERS)
        trusted = process_trust(command)
        executable = command or None
        observations.append({
            "fingerprint": digest("process", product, current_host, current_account, command or product),
            "name": f"{product} @ {current_host}",
            "vendor": vendor,
            "product": product,
            "assetType": "AGENT",
            "sourceType": "PROCESS",
            "sourceLocation": f"process:{pid}",
            "accountName": current_account,
            "processName": Path(command).name if command else product,
            "executablePath": executable,
            "autoApprove": auto_approve,
            "trustedProcess": trusted,
            "mcpServerCount": 0,
            "confidence": 92,
            "riskLevel": risk_level(auto_approve, trusted, False, 0, "PROCESS"),
            "evidenceType": "RUNNING_PROCESS",
            "evidence": {"pid": int(pid), "matchedSignature": product, "autoApproveFlagDetected": auto_approve},
        })
    return observations


def count_mcp_servers(value: Any) -> int:
    if isinstance(value, dict):
        count = len(value.get("mcpServers", {})) if isinstance(value.get("mcpServers"), dict) else 0
        return count + sum(count_mcp_servers(child) for child in value.values())
    if isinstance(value, list):
        return sum(count_mcp_servers(child) for child in value)
    return 0


def manifest_version(content: str, package_names: tuple[str, ...], file_name: str) -> str | None:
    if file_name == "package.json":
        try:
            document = json.loads(content)
            for section in ("dependencies", "devDependencies", "peerDependencies"):
                dependencies = document.get(section, {})
                for package_name in package_names:
                    if isinstance(dependencies.get(package_name), str):
                        return dependencies[package_name][:64]
        except (ValueError, TypeError):
            return None
    for line in content.splitlines():
        normalized = line.strip().lower()
        for package_name in package_names:
            prefix = package_name.lower()
            if any(normalized.startswith(prefix + operator) for operator in ("==", ">=", "~=")):
                return line.strip()[len(package_name):][:64]
    return None


def file_observations() -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    max_depth = int(os.environ.get("EAP_DISCOVERY_MAX_DEPTH", "6"))
    max_files = int(os.environ.get("EAP_DISCOVERY_MAX_FILES", "3000"))
    max_bytes = int(os.environ.get("EAP_DISCOVERY_MAX_FILE_BYTES", "1048576"))
    current_host = host_name()
    current_account = account_name()
    seen_files = 0
    seen_paths: set[str] = set()
    for root in configured_roots():
        if not root.is_dir() or not os.access(root, os.R_OK):
            continue
        for directory, directory_names, file_names in os.walk(root):
            relative_depth = len(Path(directory).relative_to(root).parts)
            if relative_depth >= max_depth:
                directory_names[:] = []
            directory_names[:] = [name for name in directory_names if name not in EXCLUDED_DIRECTORIES]
            for file_name in file_names:
                if seen_files >= max_files:
                    return observations
                candidate = Path(directory) / file_name
                normalized_candidate = str(candidate).replace("\\", "/").lower()
                if not (
                    file_name.lower() in ELIGIBLE_NAMES
                    or is_skill_manifest(candidate)
                    or is_plugin_manifest(candidate)
                    or any(marker in normalized_candidate for marker in (
                        "/.codex/", "/.claude/", "/.cursor/", "/.windsurf/", "/.kiro/", "/.opencode/",
                        "/.gemini/", "/.antigravity/", "/.openclaw/", "/.amp/", "/.aws/amazonq/", "/mcp/",
                        "/globalstorage/saoudrizwan.claude-dev/", "/globalstorage/rooveterinaryinc.roo-cline/",
                        "/globalstorage/github.copilot/", "/globalstorage/amazonwebservices.amazon-q-vscode/",
                    ))
                ):
                    continue
                path = candidate
                absolute_path = str(path.absolute())
                if absolute_path in seen_paths:
                    continue
                try:
                    if path.stat().st_size <= 0 or path.stat().st_size > max_bytes:
                        continue
                    content = path.read_text(encoding="utf-8", errors="ignore")
                except OSError:
                    continue
                seen_paths.add(absolute_path)
                seen_files += 1
                normalized = content.lower()
                if is_skill_manifest(path) or is_plugin_manifest(path):
                    plugin = is_plugin_manifest(path)
                    host = signature_for_path(path) or ("Agent Extension Host", "Unknown", (), ())
                    extension, version = extension_name(path, content, plugin)
                    credential_reference = any(marker in normalized for marker in CREDENTIAL_MARKERS)
                    network_access = any(marker in normalized for marker in NETWORK_ACCESS_MARKERS)
                    privileged_command = any(marker in normalized for marker in PRIVILEGED_COMMAND_MARKERS)
                    auto_approve = any(marker in normalized for marker in AUTO_APPROVE_MARKERS)
                    signals = security_signals(normalized)
                    observations.append({
                        "fingerprint": digest("extension", host[0], current_host, current_account, absolute_path),
                        "name": f"{extension} · {host[0]}",
                        "vendor": host[1],
                        "product": host[0],
                        "version": version,
                        "assetType": "AGENT_PLUGIN" if plugin else "AGENT_SKILL",
                        "sourceType": "PLUGIN" if plugin else "SKILL",
                        "sourceLocation": absolute_path,
                        "accountName": current_account,
                        "autoApprove": auto_approve,
                        "trustedProcess": None,
                        "mcpServerCount": 0,
                        "confidence": 90 if host[0] != "Agent Extension Host" else 78,
                        "riskLevel": extension_risk_level(auto_approve, credential_reference, network_access, privileged_command, signals),
                        "evidenceType": "AGENT_PLUGIN_MANIFEST" if plugin else "AGENT_SKILL_MANIFEST",
                        "evidence": {
                            "fileName": file_name,
                            "hostProduct": host[0],
                            "credentialReferenceDetected": credential_reference,
                            "networkAccessDetected": network_access,
                            "privilegedCommandDetected": privileged_command,
                            "autoApproveFlagDetected": auto_approve,
                            **signals,
                            "toxicFlowDetected": credential_reference and signals["networkAccessDetected"],
                        },
                    })
                    continue
                mcp_count = 0
                try:
                    mcp_count = count_mcp_servers(json.loads(content))
                except (ValueError, TypeError):
                    pass
                mcp_count = max(mcp_count, len(re.findall(r"(?im)^\s*\[mcp_servers(?:\.|\s)", content)))
                matches = [signature for signature in SIGNATURES if any(token.lower() in normalized for token in signature[3])]
                path_normalized = str(path).replace("\\", "/").lower()
                if not matches:
                    path_match = signature_for_path(path)
                    if path_match:
                        matches = [path_match]
                    elif file_name.lower() == "claude_desktop_config.json":
                        matches = [("Claude Code", "Anthropic", (), ("@anthropic-ai/claude-code",))]
                if not matches and mcp_count == 0 and "mcpservers" not in normalized and "mcp_servers" not in normalized:
                    continue
                if not matches:
                    matches = [("MCP-enabled Agent Host", "Unknown", (), ())]
                credential_reference = any(marker in normalized for marker in CREDENTIAL_MARKERS)
                auto_approve = any(marker in normalized for marker in AUTO_APPROVE_MARKERS)
                signals = security_signals(normalized)
                source = "CONFIGURATION" if any(marker in path_normalized for marker in (
                    "/.codex/", "/.claude/", "/.cursor/", "/.windsurf/", "/.kiro/", "/.opencode/",
                    "/.gemini/", "/.antigravity/", "/.openclaw/", "/.amp/", "/.aws/amazonq/", "/mcp/",
                    "/globalstorage/",
                )) else "MANIFEST"
                for product, vendor, _, package_names in matches:
                    asset_type = "MCP_CONFIGURATION" if product == "MCP-enabled Agent Host" else ("AGENT_PROJECT" if source == "MANIFEST" else "AGENT")
                    observations.append({
                        "fingerprint": digest("file", product, current_host, current_account, str(path.parent)),
                        "name": f"{product} project · {path.parent.name or current_host}",
                        "vendor": vendor,
                        "product": product,
                        "version": manifest_version(content, package_names, file_name.lower()),
                        "assetType": asset_type,
                        "sourceType": source,
                        "sourceLocation": str(path.absolute()),
                        "accountName": current_account,
                        "autoApprove": auto_approve,
                        "trustedProcess": None,
                        "mcpServerCount": mcp_count,
                        "confidence": 84 if source == "CONFIGURATION" else 76,
                        "riskLevel": risk_level(auto_approve, None, credential_reference, mcp_count, source, any(signals[key] for key in ("promptInjectionDetected", "toolPoisoningDetected", "toolShadowingDetected", "malwarePayloadDetected"))),
                        "evidenceType": "AGENT_OR_MCP_CONFIGURATION" if mcp_count else "AGENT_FRAMEWORK_MANIFEST",
                        "evidence": {"fileName": file_name, "mcpServerCount": mcp_count, "credentialReferenceDetected": credential_reference, "autoApproveFlagDetected": auto_approve, **signals, "toxicFlowDetected": credential_reference and signals["networkAccessDetected"]},
                    })
    return observations


def post_json(url: str, payload: dict[str, Any], token: str) -> dict[str, Any]:
    body = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": f"eap-discovery-collector/{VERSION}"}
    if token:
        headers["X-EAP-Discovery-Token"] = token
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


def ingestion_token() -> str:
    configured = os.environ.get("EAP_DISCOVERY_INGESTION_TOKEN", "").strip()
    if configured:
        return configured
    env_file = os.environ.get("EAP_DISCOVERY_ENV_FILE", "").strip()
    if not env_file:
        return ""
    try:
        lines = Path(env_file).read_text(encoding="utf-8").splitlines()
    except OSError:
        return ""
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip().strip("\"'")
    return values.get("EAP_DISCOVERY_INGESTION_TOKEN", "") or values.get("EAP_OBSERVABILITY_INGESTION_TOKEN", "")


def collect() -> list[dict[str, Any]]:
    return process_observations() + docker_observations() + file_observations()


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover local AI agent signals and upload sanitized observations to EAP")
    parser.add_argument("--server-url", default=os.environ.get("EAP_DISCOVERY_SERVER_URL", "http://127.0.0.1:8080/api/discovery"))
    parser.add_argument("--endpoint-key", default=os.environ.get("EAP_DISCOVERY_ENDPOINT_KEY", endpoint_key()))
    parser.add_argument("--dry-run", action="store_true", help="print sanitized observations without uploading")
    parser.add_argument("--interval-seconds", type=int, default=0, help="repeat collection at this interval; 0 means once")
    args = parser.parse_args()
    token = ingestion_token()
    while True:
        observations = collect()
        payload = {"endpointKey": args.endpoint_key, "observations": observations}
        if args.dry_run:
            print(json.dumps(payload, ensure_ascii=False, indent=2))
        else:
            post_json(args.server_url.rstrip("/") + "/endpoints/register", {
                "endpointKey": args.endpoint_key,
                "hostName": host_name(),
                "accountName": account_name(),
                "operatingSystem": platform.platform()[:128],
                "collectorVersion": VERSION,
            }, token)
            if observations:
                post_json(args.server_url.rstrip("/") + "/endpoints/observations", payload, token)
            print(f"EAP discovery upload complete: {len(observations)} observations")
        if args.interval_seconds <= 0:
            return 0
        time.sleep(args.interval_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
