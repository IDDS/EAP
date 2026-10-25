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
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import Future
from dataclasses import dataclass
from pathlib import Path
from typing import Any


VERSION = "1.1.1"
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
        "observationKind": "CONNECT_ATTEMPT",
        "processName": process_name[:256],
        "processId": event.process_id,
        "executablePath": metadata.get("executable", "")[:1024],
        "containerId": container_id,
        "workloadUid": container_id,
        "remoteAddress": event.remote_address,
        "remotePort": event.remote_port,
        "direction": "OUTBOUND",
        # sys_enter_connect proves an attempt, not socket type, TLS or transferred bytes.
        "identityKey": identity_key[:256],
    }


def load_json(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


class UploadFailure(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def read_credential_fd(descriptor: int) -> str:
    """Consume an inherited pipe once; never pass the credential to bpftrace."""
    with os.fdopen(descriptor, "rb") as stream:
        raw = stream.read(257)
    if not raw or len(raw) > 256 or any(value < 33 or value > 126 for value in raw):
        raise ValueError("INVALID_MACHINE_CREDENTIAL")
    return raw.decode("ascii")


def upload(config: dict[str, Any], observations: list[dict[str, Any]]) -> dict[str, Any]:
    server_url = str(config.get("server_url", "")).rstrip("/")
    parts = urllib.parse.urlsplit(server_url)
    if parts.scheme not in ("http", "https") or not parts.hostname or parts.username is not None or parts.password is not None or parts.query or parts.fragment:
        raise UploadFailure("INVALID_SERVER_URL")
    if server_url.startswith("http://") and not bool(config.get("allow_insecure_http")):
        raise ValueError("plain HTTP requires allow_insecure_http=true")
    credential = str(config.get("_machine_credential") or "")
    if not credential:
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
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(request, timeout=15) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise UploadFailure("INVALID_ACKNOWLEDGEMENT")
            payload = json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as error:
        error.close()
        raise UploadFailure(f"HTTP_{error.code}") from None
    except urllib.error.URLError:
        raise UploadFailure("NETWORK_ERROR") from None
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise UploadFailure("INVALID_ACKNOWLEDGEMENT") from None
    data = payload.get("data") if isinstance(payload, dict) and payload.get("success") is True else None
    if not isinstance(data, dict) or type(data.get("observationsInspected")) is not int or data["observationsInspected"] != len(observations) or type(data.get("observationsRecorded")) is not int or not 0 <= data["observationsRecorded"] <= len(observations):
        raise UploadFailure("INVALID_ACKNOWLEDGEMENT")
    return {key: data[key] for key in ("observationsInspected", "observationsRecorded")}


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
    upload(config, observations)


def start_upload(config: dict[str, Any], batch: list[dict[str, Any]], dry_run: bool) -> Future:
    """Only one bounded batch can be in flight; slow delivery never blocks capture."""
    future = Future()
    def send() -> None:
        try:
            emit_batch(config, batch, dry_run)
            future.set_result(None)
        except Exception as error:
            future.set_exception(error)
    threading.Thread(target=send, name="eap-ebpf-upload", daemon=True).start()
    return future


class DeliveryBuffer:
    def __init__(self, config, capacity, interval, retry_initial, retry_max, dry_run, now):
        self.config, self.capacity, self.interval = config, capacity, interval
        self.retry_initial, self.retry_max, self.dry_run = retry_initial, retry_max, dry_run
        self.pending: dict[tuple, dict[str, Any]] = {}
        self.inflight = None
        self.batch = {}
        self.next_attempt, self.next_flush, self.failures = now, now + interval, 0
        self.last_error = None
        self.stats = {k: 0 for k in ("eventsSeen", "eventsFiltered", "invalidLines", "observationsCoalesced",
                      "observationsDropped", "observationsAcknowledged", "observationsPrinted", "uploadAttempts", "uploadFailures")}

    def offer(self, observation):
        key = (observation["identityKey"], observation["processId"], observation["remoteAddress"], observation["remotePort"])
        if key in self.pending:
            self.stats["observationsCoalesced"] += 1
        elif len(self.pending) >= self.capacity:
            self.stats["observationsDropped"] += 1
            return
        self.pending[key] = observation

    def poll(self, now, *, force=False, start=True):
        if self.inflight is not None and self.inflight.done():
            try:
                self.inflight.result()
            except Exception as error:
                self.stats["uploadFailures"] += 1
                self.failures += 1
                self.last_error = str(error) if isinstance(error, UploadFailure) else "UPLOAD_FAILED"
                delay = min(self.retry_max, self.retry_initial * 2 ** min(self.failures - 1, 16))
                self.next_attempt = now + delay
            else:
                self.stats["observationsPrinted" if self.dry_run else "observationsAcknowledged"] += len(self.batch)
                # A new event with the same key during upload still needs its own delivery.
                for key, value in self.batch.items():
                    if self.pending.get(key) is value:
                        del self.pending[key]
                self.failures, self.last_error = 0, None
                self.next_attempt, self.next_flush = now, now + self.interval
            self.inflight, self.batch = None, {}
        if start and self.inflight is None and self.pending and now >= self.next_attempt and (force or len(self.pending) >= self.capacity or now >= self.next_flush):
            self.batch = dict(self.pending)
            self.stats["uploadAttempts"] += 1
            self.inflight = start_upload(self.config, list(self.batch.values()), self.dry_run)

    def health(self, now, *, stopping=False):
        return dict(self.stats, event="observer_health", sensorType=SENSOR_TYPE, collectorVersion=VERSION,
                    state="STOPPED" if stopping else "DEGRADED" if self.failures else "RUNNING",
                    captureStatus="EVENTS_OBSERVED" if self.stats["eventsSeen"] else "NO_EVENTS",
                    queuedObservations=len(self.pending), queueCapacity=self.capacity,
                    inFlightObservations=len(self.batch), retryInSeconds=max(0, round(self.next_attempt-now, 3)),
                    lastDeliveryError=self.last_error, kernelLossAccounting="UNAVAILABLE", dryRun=self.dry_run)

    def finish(self, now):
        self.poll(now, force=True)
        if self.inflight is not None:
            try:
                self.inflight.result(timeout=2)
            except Exception:
                pass
            self.poll(time.monotonic(), start=False)


class EventLines:
    """Bound incomplete lines and read the pipe directly, without text-buffer read-ahead."""
    def __init__(self):
        self.pending = b""
        self.discarding = False

    def feed(self, data):
        parts = data.split(b"\n")
        lines, invalid = [], 0
        for index, part in enumerate(parts):
            complete = index < len(parts) - 1
            if self.discarding:
                if complete:
                    self.discarding = False
                continue
            if len(self.pending) + len(part) > 4096:
                self.pending = b""
                self.discarding = not complete
                invalid += 1
                continue
            self.pending += part
            if complete:
                try:
                    lines.append(self.pending.decode("utf-8"))
                except UnicodeDecodeError:
                    invalid += 1
                self.pending = b""
        return lines, invalid


def observe(args: argparse.Namespace) -> int:
    if sys.platform != "linux":
        print(json.dumps({"sensorType": SENSOR_TYPE, "warning": "eBPF observer requires Linux"}))
        return 0
    if os.geteuid() != 0:
        raise PermissionError("eBPF observer must run as root")
    if not shutil.which("bpftrace"):
        print(json.dumps({"event": "observer_health", "sensorType": SENSOR_TYPE, "state": "UNAVAILABLE", "reason": "BPFTRACE_MISSING"}))
        return 0
    if not Path("/sys/kernel/btf/vmlinux").is_file():
        print(json.dumps({"event": "observer_health", "sensorType": SENSOR_TYPE, "state": "UNAVAILABLE", "reason": "BTF_MISSING"}))
        return 0

    config = load_json(Path(args.config))
    if getattr(args, "credential_fd", None) is not None:
        config["_machine_credential"] = read_credential_fd(args.credential_fd)
    settings = config.get("ebpf_observer") if isinstance(config.get("ebpf_observer"), dict) else {}
    if str(settings.get("mode", "auto")).lower() == "off":
        print(json.dumps({"sensorType": SENSOR_TYPE, "status": "disabled"}))
        return 0
    batch_interval = max(5, min(300, int(settings.get("batch_interval_seconds", 30))))
    max_events = max(1, min(1000, int(settings.get("max_events", 1000))))
    retry_initial = max(1, min(300, int(settings.get("retry_initial_seconds", 5))))
    retry_max = max(retry_initial, min(3600, int(settings.get("retry_max_seconds", 300))))
    status_interval = max(5, min(300, int(settings.get("status_interval_seconds", 30))))
    program = Path(args.program)
    if not program.is_file():
        raise FileNotFoundError(program)

    process = subprocess.Popen(
        ["bpftrace", "-q", str(program)],
        stdout=subprocess.PIPE,
        stderr=None,
        bufsize=0,
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
    next_status = started
    host_name = socket.gethostname()
    workloads = docker_workloads()
    buffer = DeliveryBuffer(config, max_events, batch_interval, retry_initial, retry_max, args.dry_run, started)
    next_workloads = started + batch_interval
    reader = EventLines()

    try:
        while not stop:
            now = time.monotonic()
            if args.duration > 0 and now - started >= args.duration:
                break
            buffer.poll(now)
            if now >= next_status:
                print(json.dumps(buffer.health(now)), flush=True)
                next_status = now + status_interval
            timeout = 0.1 if buffer.inflight is not None else 1.0
            for key, _mask in selector.select(timeout):
                data = os.read(key.fileobj.fileno(), 65536)
                if not data:
                    raise RuntimeError("Capture pipe closed")
                lines, invalid = reader.feed(data)
                buffer.stats["invalidLines"] += invalid
                for line in lines:
                    event = parse_event_line(line)
                    if not event:
                        buffer.stats["invalidLines"] += 1
                        continue
                    buffer.stats["eventsSeen"] += 1
                    observation = build_observation(event, host_name, workloads)
                    if not observation:
                        buffer.stats["eventsFiltered"] += 1
                        continue
                    buffer.offer(observation)
            now = time.monotonic()
            buffer.poll(now)
            if now >= next_workloads:
                workloads = docker_workloads()
                next_workloads = now + batch_interval
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
        buffer.finish(time.monotonic())
        print(json.dumps(buffer.health(time.monotonic(), stopping=True)), flush=True)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description="EAP Linux eBPF connect observer")
    result.add_argument("--config", default="/etc/eap-discovery-agent/config.json")
    result.add_argument("--program", default="/usr/local/lib/eap-discovery-agent/eap-connect.bt")
    result.add_argument("--duration", type=int, default=0, help="stop after N seconds; 0 runs continuously")
    result.add_argument("--dry-run", action="store_true", help="print sanitized observations without upload")
    result.add_argument("--credential-fd", type=int, help="read a machine credential from an inherited pipe, then close it")
    result.set_defaults(handler=observe)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        return int(args.handler(args))
    except Exception:  # Never log server bodies, credentials or arbitrary exception messages.
        print(json.dumps({"event": "observer_health", "sensorType": SENSOR_TYPE,
                          "state": "FAILED", "reason": "CONFIG_OR_CAPTURE_ERROR"}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
