"""Bounded stdio proof of concept. Run only as an explicitly configured wrapper.

Example: relay.py --server demo --tool calculate_sum --events events.jsonl --
         python3 server.py
"""
import argparse
import json
import os
import queue
import subprocess
import sys
import threading

from observer import McpObserver, otlp
from runtime_export import RuntimeExporter, RuntimeLease


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", required=True)
    parser.add_argument("--tool", action="append", default=[])
    parser.add_argument("--events")
    parser.add_argument("--runtime-url")
    parser.add_argument("--runtime-credentials-fd", type=int)
    parser.add_argument("--allow-insecure-runtime-http", action="store_true")
    parser.add_argument("--export-timeout", type=float, default=1.0)
    parser.add_argument("--trace-id")
    parser.add_argument("--parent-span-id")
    parser.add_argument("--agent-id")
    parser.add_argument("--exit-timeout", type=float, default=5,
                        help="seconds to wait for child exit after its stdout closes (0.1–60)")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("an explicit MCP server command is required")
    if not 0.1 <= args.exit_timeout <= 60:
        parser.error("exit timeout must be between 0.1 and 60 seconds")
    if bool(args.runtime_url) != (args.runtime_credentials_fd is not None):
        parser.error("runtime URL and credential pipe must be configured together")
    if not args.events and not args.runtime_url:
        parser.error("an events file or runtime export is required")
    if args.runtime_url and args.agent_id:
        parser.error("runtime identity is assigned by authenticated EAP ingress")
    if not args.runtime_url and args.allow_insecure_runtime_http:
        parser.error("insecure HTTP option requires runtime export")
    runtime = None
    lease = None
    try:
        if args.runtime_url:
            lease = RuntimeLease(args.runtime_credentials_fd)
            # Initial delivery has a bounded wait; unavailable identity never
            # becomes a reason to replay or delay the business tool indefinitely.
            lease.ready.wait(timeout=1)
            runtime = RuntimeExporter(args.runtime_url, lease,
                                      allow_http=args.allow_insecure_runtime_http,
                                      timeout=args.export_timeout)
    except (ValueError, OSError):
        if lease:
            lease.close()
        parser.error("invalid runtime export configuration")
    events = queue.Queue(maxsize=256)
    counts = {"dropped": 0, "export_failed": 0, "observer_failed": 0, "exit_timeout": False}

    def emit(event):
        if runtime:
            runtime.submit(event)
        if args.events:
            try:
                events.put_nowait({"event": event, "otlp": otlp(event, args.agent_id)})
            except queue.Full:
                counts["dropped"] += 1

    observer = McpObserver(args.server, args.tool, emit, trace_id=args.trace_id, parent_span_id=args.parent_span_id)

    def observe(direction, chunk):
        try:
            observer.feed(direction, chunk)
        except Exception:
            counts["observer_failed"] += 1
            observer.close()

    # Refuse overwrite/symlinks. This diagnostic file contains only bounded
    # metadata, never protocol bodies, environment values or raw request IDs.
    output = None
    if args.events:
        fd = os.open(args.events, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        output = os.fdopen(fd, "w", encoding="utf-8")

    def export():
        with output:
            while True:
                item = events.get()
                if item is None:
                    return
                try:
                    output.write(json.dumps(item, separators=(",", ":")) + "\n")
                    output.flush()
                except OSError:
                    counts["export_failed"] += 1

    exporter = threading.Thread(target=export, daemon=True)
    if output:
        exporter.start()
    # The wrapper does not need or forward EAP machine credentials to the MCP
    # subprocess. Other user-supplied server configuration is left intact.
    child_env = {k: v for k, v in os.environ.items() if not k.startswith("EAP_")}
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, env=child_env, close_fds=True)
    broken = threading.Event()

    def copy_client():
        try:
            while True:
                chunk = os.read(sys.stdin.fileno(), 8192)
                if not chunk:
                    break
                observe("client", chunk)
                child.stdin.write(chunk)
                child.stdin.flush()
        except (BrokenPipeError, OSError):
            broken.set()
        finally:
            try:
                child.stdin.close()
            except (BrokenPipeError, OSError):
                broken.set()

    reader = threading.Thread(target=copy_client, daemon=True)
    reader.start()
    try:
        while True:
            chunk = os.read(child.stdout.fileno(), 8192)
            if not chunk:
                break
            observe("server", chunk)
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
    except (BrokenPipeError, OSError):
        broken.set()
        child.terminate()
    finally:
        observer.close()
        # A saturated or failed exporter cannot prevent child cleanup.
        if output:
            try:
                events.put(None, timeout=2)
            except queue.Full:
                counts["dropped"] += 1
            exporter.join(timeout=2)
    try:
        code = child.wait(timeout=args.exit_timeout)
    except subprocess.TimeoutExpired:
        counts["exit_timeout"] = True
        child.terminate()
        try:
            code = child.wait(timeout=2)
        except subprocess.TimeoutExpired:
            child.kill()
            code = child.wait(timeout=2)
    summary = {"observer": "eap-mcp-stdio-observer", "degraded": observer.degraded,
               "droppedFrames": observer.dropped_frames, "deliveryFailures": observer.delivery_failures,
               "droppedEvents": counts["dropped"], "exportFailures": counts["export_failed"],
               "observerFailures": counts["observer_failed"], "childExitTimeout": counts["exit_timeout"]}
    if runtime:
        summary["runtimeExport"] = runtime.close()
    print(json.dumps(summary), file=sys.stderr)
    return code if not broken.is_set() else 1


if __name__ == "__main__":
    raise SystemExit(main())
