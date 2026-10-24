#!/usr/bin/env python3
"""PoC node launcher: Kubernetes Secret -> anonymous pipe, never a credential file."""
import argparse
import base64
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="/etc/eap-discovery-agent/ebpf.json")
    parser.add_argument("--observer", default="/usr/local/lib/eap-discovery-agent/eap_ebpf_observer.py")
    parser.add_argument("--program", default="/usr/local/lib/eap-discovery-agent/eap-connect.bt")
    args = parser.parse_args()
    with open(args.config) as handle:
        config = json.load(handle)
    result = subprocess.run([
        "/usr/bin/kubectl", "--kubeconfig=/etc/kubernetes/admin.conf",
        "--cache-dir=/run/eap-ebpf-observer/kube-cache", "--request-timeout=15s",
        "-n", "eap", "get", "secret", "eap-ebpf-observer", "-o", "json",
    ], capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError("CREDENTIAL_UNAVAILABLE")
    secret = json.loads(result.stdout)
    credential = base64.b64decode(secret["data"]["credential"], validate=True)
    if not 1 <= len(credential) <= 256 or any(value < 33 or value > 126 for value in credential):
        raise RuntimeError("INVALID_MACHINE_CREDENTIAL")
    del secret, result
    stopped = threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stopped.set())
    reader, writer = os.pipe()
    try:
        os.write(writer, credential)
    finally:
        os.close(writer)
    try:
        child = subprocess.Popen([
            sys.executable, args.observer, "--config", args.config,
            "--program", args.program, "--credential-fd", str(reader),
        ], pass_fds=(reader,), stdin=subprocess.DEVNULL)
    finally:
        os.close(reader)
    # Share the observer's fixed-origin, no-proxy, no-redirect transport policy.
    sys.path.insert(0, os.path.dirname(args.observer))
    from eap_ebpf_observer import NoRedirect
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    next_heartbeat = 0.0
    try:
        while child.poll() is None and not stopped.is_set():
            if time.monotonic() >= next_heartbeat:
                request = urllib.request.Request(
                    config["server_url"].rstrip("/") + "/api/discovery/v1/agent/heartbeat",
                    data=json.dumps(config["heartbeat"]).encode(),
                    headers={"Content-Type": "application/json", "X-EAP-Agent-Credential": credential.decode("ascii")},
                )
                healthy = False
                try:
                    with opener.open(request, timeout=15) as response:
                        body = response.read(65537)
                        healthy = len(body) <= 65536 and json.loads(body).get("success") is True
                except Exception:
                    pass
                print(json.dumps({"event": "endpoint_heartbeat", "accepted": healthy}), flush=True)
                next_heartbeat = time.monotonic() + 60
            stopped.wait(1)
        return 0 if stopped.is_set() else (child.returncode or 1)
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        print('{"event":"observer_launcher","state":"UNAVAILABLE","reason":"STARTUP_FAILED"}', flush=True)
        sys.exit(1)
