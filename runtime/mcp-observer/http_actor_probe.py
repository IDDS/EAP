"""Trusted, fixed HTTP acceptance host; launched only by the dedicated image.

No arbitrary endpoint, payload, model or task-selected command. Runtime lease
delivery uses the same anonymous pipe and renewal path as the stdio observer.
"""
import time
_PROCESS_START = time.monotonic_ns()

import argparse
import json
import os
import select
import subprocess
import sys

from http_transport import ObservedHttpClient
from observer import _unique_object
from runtime_export import RuntimeExporter, RuntimeLease

_IMPORT_DONE = time.monotonic_ns()
VERSION = "EAP_MCP_HTTP_PROBE_V1"
SERVER_TIMINGS = {"serverImportMs", "serverBindMs", "serverFqdnMs", "serverBeforeReadyMs"}


def elapsed_ms(start):
    return (time.monotonic_ns() - start) // 1_000_000


def valid_server_timings(value):
    return (type(value) is dict and set(value) == SERVER_TIMINGS
            and all(type(n) is int and 0 <= n <= 60_000 for n in value.values())
            and value["serverFqdnMs"] <= value["serverBindMs"] <= value["serverBeforeReadyMs"]
            and value["serverImportMs"] <= value["serverBeforeReadyMs"])


def readiness(pipe):
    deadline, raw = time.monotonic() + 5, bytearray()
    while time.monotonic() < deadline and len(raw) <= 1024:
        if not select.select([pipe], [], [], min(0.1, max(0, deadline - time.monotonic())))[0]:
            continue
        chunk = os.read(pipe.fileno(), 1024)
        if not chunk:
            break
        raw.extend(chunk)
        if b"\n" in raw:
            value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object)
            if (value.get("version") == "EAP_MCP_HTTP_SERVER_V1"
                    and type(value.get("port")) is int and 1 <= value["port"] <= 65535
                    and value.get("credentialChannelsAbsent") is True
                    and valid_server_timings(value.get("startup"))):
                return value
            break
    raise ValueError("synthetic server readiness unconfirmed")


def consume(client, payload, headers=None, *, trace_id, parent_span_id):
    body = json.dumps(payload, separators=(",", ":")).encode()
    with client.post(body, headers=headers, trace_id=trace_id, parent_span_id=parent_span_id) as response:
        raw = bytearray()
        while chunk := response.read(4096):
            if len(raw) + len(chunk) > 65536:
                raise ValueError("synthetic response too large")
            raw.extend(chunk)
        return response.status, bytes(raw), response.observer.core


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--tool", action="append", default=[])
    parser.add_argument("--runtime-url", required=True)
    parser.add_argument("--runtime-credentials-fd", required=True, type=int)
    parser.add_argument("--allow-insecure-runtime-http", action="store_true")
    parser.add_argument("--trace-id", required=True)
    parser.add_argument("--parent-span-id", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command or args.tool != ["observation_probe"]:
        parser.error("fixed synthetic HTTP service required")
    startup = {"version": 1, "observerImportMs": (_IMPORT_DONE - _PROCESS_START) // 1_000_000}
    mark = time.monotonic_ns()
    lease = RuntimeLease(args.runtime_credentials_fd)
    exporter, child, proof = None, None, None
    events = []
    failed = False
    try:
        lease.ready.wait(1)
        if lease.snapshot() is None:
            raise ValueError("runtime identity unavailable")
        startup["identityReadyMs"] = elapsed_ms(mark)
        mark = time.monotonic_ns()
        exporter = RuntimeExporter(args.runtime_url, lease,
                                   allow_http=args.allow_insecure_runtime_http)
        startup["exporterStartMs"] = elapsed_ms(mark)
        mark = time.monotonic_ns()
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, close_fds=True,
                                 env={k: v for k, v in os.environ.items() if not k.startswith("EAP_")})
        startup["serverSpawnMs"] = elapsed_ms(mark)
        mark = time.monotonic_ns()
        ready = readiness(child.stdout)
        startup["serverReadyWaitMs"] = elapsed_ms(mark)
        startup.update(ready["startup"])
        startup["observerBeforeReadyMs"] = elapsed_ms(_PROCESS_START)
        print(json.dumps({"version": VERSION, "phase": "READY", "startup": startup}), flush=True)
        raw = sys.stdin.buffer.read(1025)
        if len(raw) > 1024:
            raise ValueError("trusted control too large")
        control = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object)
        if (set(control) != {"version", "minimumCredentialSequence"} or control["version"] != VERSION
                or type(control["minimumCredentialSequence"]) is not int
                or not 1 <= control["minimumCredentialSequence"] <= 2**53):
            raise ValueError("invalid trusted start control")
        deadline = time.monotonic() + 1
        while time.monotonic() < deadline:
            current = lease.snapshot()
            if current and current["sequence"] >= control["minimumCredentialSequence"]:
                break
            time.sleep(0.01)
        else:
            raise ValueError("current runtime delivery unconfirmed")

        def emit(event):
            events.append(event)
            exporter.submit(event)

        endpoint = f"http://127.0.0.1:{ready['port']}/mcp"
        requests = 0
        for version in ["2025-11-25", "2026-07-28"]:
            client = ObservedHttpClient(endpoint, server=args.server, tools=args.tool, emit=emit,
                                        protocol_version=version, allow_http=True, timeout=2)
            # Attach this original trusted task to every POST. The MCP body and
            # headers never supply EAP identity or trace attribution.
            def call(payload, headers=None):
                return consume(client, payload, headers, trace_id=args.trace_id,
                               parent_span_id=args.parent_span_id)
            if version == "2025-11-25":
                status, raw, _ = call({"jsonrpc": "2.0", "id": "init", "method": "initialize",
                    "params": {"protocolVersion": version, "capabilities": {},
                               "clientInfo": {"name": "eap-http-probe", "version": "1"}}})
                initialized = json.loads(raw)
                if status != 200 or initialized.get("id") != "init" or initialized["result"]["protocolVersion"] != version:
                    raise ValueError("initialization unconfirmed")
                status, raw, _ = call({"jsonrpc": "2.0", "method": "notifications/initialized"})
                if status != 202 or raw:
                    raise ValueError("initialization notification unconfirmed")
                requests += 2
            for index, mode in enumerate(["success", "failure", "disconnect"], 1):
                params = {"name": "observation_probe", "arguments": {"mode": mode}}
                headers = None
                if version == "2026-07-28":
                    params["_meta"] = {"io.modelcontextprotocol/protocolVersion": version,
                                      "io.modelcontextprotocol/clientInfo": {"name": "eap-http-probe", "version": "1"},
                                      "io.modelcontextprotocol/clientCapabilities": {}}
                    headers = {"Mcp-Method": "tools/call", "Mcp-Name": "observation_probe"}
                before = len(events)
                status, _, observer = call({"jsonrpc": "2.0", "id": index,
                                                       "method": "tools/call", "params": params}, headers)
                requests += 1
                expected = {"success": "SUCCEEDED", "failure": "FAILED", "disconnect": "UNKNOWN"}[mode]
                if (status != 200 or len(events) != before + 1 or events[-1]["status"] != expected
                        or observer.degraded or observer.delivery_failures):
                    raise ValueError("synthetic HTTP outcome unconfirmed")
        if child.poll() is not None:
            raise ValueError("synthetic HTTP process exited early")
        proof = {"version": VERSION, "transport": "STREAMABLE_HTTP", "synthetic": True,
                 "protocolVersions": ["2025-11-25", "2026-07-28"],
                 "protocolOutcomes": [e["status"] for e in events], "httpRequests": requests,
                 "credentialChannelsAbsent": ready["credentialChannelsAbsent"]}
    except Exception:
        failed = True  # Never serialize exception text, headers, lease or bodies.
    finally:
        if child:
            child.terminate()
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=2)
            child.stdout.close()
        summary = {"observer": "eap-mcp-http-observer", "degraded": failed,
                   "observerFailures": int(failed), "droppedFrames": 0,
                   "deliveryFailures": 0, "droppedEvents": 0, "exportFailures": 0,
                   "childExitTimeout": False}
        if not failed:
            summary["httpStartup"] = startup
        if exporter:
            summary["runtimeExport"] = exporter.close()
        else:
            lease.close()
        if proof:
            print(json.dumps(proof), flush=True)
        print(json.dumps(summary), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
