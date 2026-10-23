#!/usr/bin/env python3
"""Linux eBPF connect observer for EAP Discovery Agent.

The adapter consumes bpftrace connect events, enriches them with /proc and
read-only Docker metadata, then uploads only Agent-like network observations
through the enrolled Discovery Agent machine credential.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import selectors
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


VERSION = "1.0.0"
SENSOR_TYPE = "EBPF"
EVENT_PREFIX = "EAPBPF"
CONTAINER_PATTERN = re.compile(r"(?i)([0-9a-f]{64})")
AGENT_MARKERS = (
    "boss-agent", "helloboss", "agent-worker", "ai-agent", "agent.exe", "agent.py",
    "langchain", "langgraph", "crewai", "autogen", "semantic-kernel", "semantic_kernel",
    "openai-agents", "dify", "flowise", "langflow", "codex", "claude", "gemini",
    "copilot", "mastra",
)
IGNORED_MARKERS = (
    "eap-discovery-agent", "eap-content-proxy", "eap_ebpf_observer", "tat_agent", "/qcloud/",
    "node_exporter", "telegraf", "otelcol", "opentelemetry-collector",
)


@dataclass(frozen=True)
class ConnectEvent:
    timestamp_ns: int
    process_id: int
    process_name: str
    family: int
    remote_address: str
    remote_port: int


@dataclass(frozen=True)
class DockerWorkload:
    container_id: str
    name: str
    image: str
    project: str
    service: str


def parse_event_line(line: str) -> ConnectEvent | None:
    fields = line.rstrip("\n").split("\t")
    if len(fields) != 7 or fields[0] != EVENT_PREFIX:
        return None
    try:
        timestamp_ns = int(fields[1])
        process_id = int(fields[2])
        family = int(fields[4])
        remote_port = int(fields[6])
        address = ipaddress.ip_address(fields[5])
    except (ValueError, TypeError):
        return None
    if process_id <= 0 or family not in (4, 6) or not 0 <= remote_port <= 65535:
        return None
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
        address = address.ipv4_mapped
        family = 4
    return ConnectEvent(timestamp_ns, process_id, fields[3].strip(), family, str(address), remote_port)


def read_text(path: Path, limit: int = 8192) -> str:
    try:
        return path.read_bytes()[:limit].decode("utf-8", errors="replace")
    except OSError:
        return ""


def process_metadata(process_id: int) -> dict[str, str]:
    root = Path("/proc") / str(process_id)
    try:
        executable = os.readlink(root / "exe")
    except OSError:
        executable = ""
    return {
        "executable": executable,
        "cmdline": read_text(root / "cmdline").replace("\x00", " ").strip(),
        "cgroup": read_text(root / "cgroup"),
    }


def docker_workloads() -> dict[str, DockerWorkload]:
    if not shutil.which("docker"):
        return {}
    command = [
        "docker", "ps", "--no-trunc", "--format",
        '{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}',
    ]
    try:
        output = subprocess.run(command, check=True, capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return {}
    result: dict[str, DockerWorkload] = {}
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) != 5 or not fields[0]:
            continue
        workload = DockerWorkload(fields[0].lower(), fields[1], fields[2], fields[3], fields[4])
        result[workload.container_id] = workload
    return result


def workload_for_cgroup(cgroup: str, workloads: dict[str, DockerWorkload]) -> DockerWorkload | None:
    match = CONTAINER_PATTERN.search(cgroup)
    if not match:
        return None
    container_id = match.group(1).lower()
    if container_id in workloads:
        return workloads[container_id]
    return next((workload for key, workload in workloads.items() if key.startswith(container_id)), None)


def docker_identity(host_name: str, workload: DockerWorkload) -> str:
    project = workload.project or workload.name
    service = workload.service or workload.name
    return f"docker:{host_name}:{project}:{service}"


def looks_agent(signal_value: str) -> bool:
    signal_lower = signal_value.lower()
    if any(marker in signal_lower for marker in IGNORED_MARKERS):
        return False
    return any(marker in signal_lower for marker in AGENT_MARKERS)


def build_observation(
    event: ConnectEvent,
    host_name: str,
    workloads: dict[str, DockerWorkload],
    metadata: dict[str, str] | None = None,
) -> dict[str, Any] | None:
    metadata = metadata if metadata is not None else process_metadata(event.process_id)
    workload = workload_for_cgroup(metadata.get("cgroup", ""), workloads)
    process_name = workload.name if workload else event.process_name
    signal_value = " ".join((
        process_name,
        event.process_name,
        metadata.get("executable", ""),
        metadata.get("cmdline", ""),
        workload.image if workload else "",
        workload.project if workload else "",
        workload.service if workload else "",
    ))
    if not looks_agent(signal_value):
        return None
    identity_key = docker_identity(host_name, workload) if workload else (
        f"process:{host_name}:{metadata.get('executable') or event.process_name}"
    )
    container_id = workload.container_id if workload else ""
    return {
        "processName": process_name,
        "processId": event.process_id,
        "executablePath": metadata.get("executable", ""),
        "containerId": container_id,
        "cgroupId": container_id,
        "workloadUid": container_id,
        "remoteAddress": event.remote_address,
        "remotePort": event.remote_port,
        "transport": "TCP",
        "direction": "OUTBOUND",
        "protocolHint": "HTTPS" if event.remote_port == 443 else "TCP",
        "bytesSent": 0,
        "bytesReceived": 0,
        "identityKey": identity_key,
    }


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def upload(config: dict[str, Any], observations: list[dict[str, Any]]) -> dict[str, Any]:
    server_url = str(config.get("server_url", "")).rstrip("/")
    if not server_url:
        raise ValueError("server_url is required")
    if server_url.startswith("http://") and not bool(config.get("allow_insecure_http")):
        raise ValueError("plain HTTP requires allow_insecure_http=true")
    state_dir = Path(str(config.get("state_dir") or "/var/lib/eap-discovery-agent"))
    state = load_json(state_dir / "state.json")
    credential = str(state.get("credential") or "")
    if not credential:
        raise ValueError("Discovery Agent is not enrolled")
    body = json.dumps({
        "sensorType": SENSOR_TYPE,
        "collectorVersion": f"eap-ebpf-bpftrace/{VERSION}",
        "observations": observations,
    }, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        server_url + "/api/discovery/v1/agent/host-observations",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-EAP-Agent-Credential": credential,
            "User-Agent": f"eap-ebpf-bpftrace/{VERSION}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read(4096).decode("utf-8", errors="replace")
        raise RuntimeError(f"EAP returned HTTP {error.code}: {detail}") from error
    return payload.get("data", payload) if isinstance(payload, dict) else {"response": payload}


def emit_batch(config: dict[str, Any], observations: list[dict[str, Any]], dry_run: bool) -> None:
    if not observations:
        return
    payload = {
        "sensorType": SENSOR_TYPE,
        "collectorVersion": f"eap-ebpf-bpftrace/{VERSION}",
        "observations": observations,
    }
    if dry_run:
        print(json.dumps(payload, ensure_ascii=False, indent=2), flush=True)
        return
    response = upload(config, observations)
    print(json.dumps({"uploaded": len(observations), "response": response}, ensure_ascii=False), flush=True)


def observe(args: argparse.Namespace) -> int:
    if sys.platform != "linux":
        print(json.dumps({"sensorType": SENSOR_TYPE, "warning": "eBPF observer requires Linux"}))
        return 0
    if os.geteuid() != 0:
        raise PermissionError("eBPF observer must run as root")
    if not shutil.which("bpftrace"):
        print(json.dumps({"sensorType": SENSOR_TYPE, "warning": "bpftrace is not installed"}))
        return 0
    if not Path("/sys/kernel/btf/vmlinux").is_file():
        print(json.dumps({"sensorType": SENSOR_TYPE, "warning": "kernel BTF is unavailable"}))
        return 0

    config = load_json(Path(args.config))
    settings = config.get("ebpf_observer") if isinstance(config.get("ebpf_observer"), dict) else {}
    if str(settings.get("mode", "auto")).lower() == "off":
        print(json.dumps({"sensorType": SENSOR_TYPE, "status": "disabled"}))
        return 0
    batch_interval = max(5, min(300, int(settings.get("batch_interval_seconds", 30))))
    max_events = max(1, min(1000, int(settings.get("max_events", 1000))))
    program = Path(args.program)
    if not program.is_file():
        raise FileNotFoundError(program)

    process = subprocess.Popen(
        ["bpftrace", "-q", str(program)],
        stdout=subprocess.PIPE,
        stderr=None,
        text=True,
        bufsize=1,
    )
    if process.stdout is None:
        raise RuntimeError("unable to read bpftrace output")
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    stop = False

    def request_stop(_signum: int, _frame: Any) -> None:
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    started = time.monotonic()
    next_flush = started + batch_interval
    host_name = socket.gethostname()
    workloads = docker_workloads()
    observations: dict[tuple[Any, ...], dict[str, Any]] = {}

    def flush_observations() -> bool:
        if not observations:
            return True
        try:
            emit_batch(config, list(observations.values()), args.dry_run)
        except Exception as error:
            print(f"eap-ebpf-observer: batch upload failed: {error}", file=sys.stderr, flush=True)
            return False
        observations.clear()
        return True

    try:
        while not stop:
            now = time.monotonic()
            if args.duration > 0 and now - started >= args.duration:
                break
            timeout = max(0.1, min(1.0, next_flush - now))
            for key, _mask in selector.select(timeout):
                line = key.fileobj.readline()
                if not line:
                    if process.poll() is not None:
                        raise RuntimeError(f"bpftrace exited with status {process.returncode}")
                    continue
                event = parse_event_line(line)
                if not event:
                    continue
                observation = build_observation(event, host_name, workloads)
                if not observation:
                    continue
                identity = (
                    observation["identityKey"], observation["processId"],
                    observation["remoteAddress"], observation["remotePort"],
                )
                observations[identity] = observation
                if len(observations) >= max_events:
                    flush_observations()
            now = time.monotonic()
            if now >= next_flush:
                flush_observations()
                workloads = docker_workloads()
                next_flush = now + batch_interval
        had_final_observations = bool(observations)
        flush_observations()
        if args.dry_run and not had_final_observations:
            print(json.dumps({"sensorType": SENSOR_TYPE, "observations": []}), flush=True)
        return 0
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="EAP Linux eBPF connect observer")
    result.add_argument("--config", default="/etc/eap-discovery-agent/config.json")
    result.add_argument("--program", default="/usr/local/lib/eap-discovery-agent/eap-connect.bt")
    result.add_argument("--duration", type=int, default=0, help="stop after N seconds; 0 runs continuously")
    result.add_argument("--dry-run", action="store_true", help="print sanitized observations without upload")
    result.set_defaults(handler=observe)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return int(args.handler(args))
    except Exception as error:  # systemd receives a non-zero status and can restart the adapter.
        print(f"eap-ebpf-observer: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
