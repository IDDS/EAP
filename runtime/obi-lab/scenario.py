"""Synthetic ordinary-container control for the OBI visibility experiment.

The image supplies the same MCP service and HTTP observer used by the EAP Actor.
No model, production tool, EAP credential or external endpoint is used here.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, "/opt/eap-mcp")
from http_actor_probe import consume, readiness
from http_transport import ObservedHttpClient


def run_batch(port, batch):
    events, timings = [], []
    for version in ["2025-11-25", "2026-07-28"]:
        client = ObservedHttpClient(
            f"http://127.0.0.1:{port}/mcp", server="eap-obi-synthetic",
            tools=["observation_probe"], emit=events.append,
            protocol_version=version, allow_http=True, timeout=2)

        def call(body, headers=None):
            before = time.monotonic_ns()
            result = consume(client, body, headers, trace_id=f"{batch + 1:032x}",
                             parent_span_id="0000000000000001")
            timings.append((time.monotonic_ns() - before) / 1_000_000)
            return result

        if version == "2025-11-25":
            status, raw, _ = call({"jsonrpc": "2.0", "id": "init", "method": "initialize",
                                  "params": {"protocolVersion": version, "capabilities": {},
                                             "clientInfo": {"name": "eap-obi-control", "version": "1"}}})
            assert status == 200 and json.loads(raw)["result"]["protocolVersion"] == version
            status, raw, _ = call({"jsonrpc": "2.0", "method": "notifications/initialized"})
            assert status == 202 and not raw
        for index, mode in enumerate(["success", "failure", "disconnect"], 1):
            params = {"name": "observation_probe", "arguments": {"mode": mode}}
            headers = None
            if version == "2026-07-28":
                params["_meta"] = {
                    "io.modelcontextprotocol/protocolVersion": version,
                    "io.modelcontextprotocol/clientInfo": {"name": "eap-obi-control", "version": "1"},
                    "io.modelcontextprotocol/clientCapabilities": {}}
                headers = {"Mcp-Method": "tools/call", "Mcp-Name": "observation_probe"}
            status, _, _ = call({"jsonrpc": "2.0", "id": index, "method": "tools/call",
                                 "params": params}, headers)
            assert status == 200
    assert [x["status"] for x in events] == ["SUCCEEDED", "FAILED", "UNKNOWN"] * 2
    return timings


def main():
    out = Path("/out")
    child = subprocess.Popen(
        ["/usr/local/bin/python3", "/opt/eap-mcp/http_actor_server.py"],
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        close_fds=True, env={k: v for k, v in os.environ.items() if not k.startswith("EAP_")})
    try:
        ready = readiness(child.stdout)
        assert ready["port"] == 28761
        (out / "ordinary-ready.json").write_text(json.dumps({"port": ready["port"], "childPid": child.pid}))
        for phase in ["baseline", "observed"]:
            deadline = time.monotonic() + 480
            while not (out / (phase + ".request")).exists():
                if time.monotonic() > deadline:
                    raise TimeoutError("bounded phase wait expired")
                time.sleep(0.1)
            request = json.loads((out / (phase + ".request")).read_text())
            assert request == {"batches": 20}
            before = time.monotonic()
            durations = []
            for batch in range(request["batches"]):
                durations.extend(run_batch(ready["port"], batch))
            value = {"phase": phase, "batches": 20, "httpRequests": 160, "toolCalls": 120,
                     "outcomes": {"SUCCEEDED": 40, "FAILED": 40, "UNKNOWN": 40},
                     "wallSeconds": time.monotonic() - before, "latencyMs": durations,
                     "synthetic": True, "modelCalls": 0}
            with (out / (phase + ".json")).open("x") as f:
                json.dump(value, f)
        # Keep the positive control visible while the gVisor sample is executed.
        deadline = time.monotonic() + 600
        while not (out / "stop.request").exists() and time.monotonic() < deadline:
            time.sleep(0.2)
    finally:
        child.terminate()
        try:
            child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=3)
        child.stdout.close()


if __name__ == "__main__":
    main()
