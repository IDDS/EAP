"""Synthetic read-only MCP service for the dedicated EAP acceptance image.

No models, external services, filesystem writes or business tools.
"""
import json
import os
import sys


def main():
    if any(key.startswith("EAP_") for key in os.environ):
        return 2
    for line in sys.stdin.buffer:
        request = json.loads(line)
        method, identifier = request.get("method"), request.get("id")
        if method == "notifications/initialized":
            continue
        if method == "initialize":
            result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                      "serverInfo": {"name": "eap-synthetic-mcp", "version": "1"}}
        elif method == "tools/call":
            if request["params"]["name"] != "observation_probe":
                return 3
            mode = request["params"]["arguments"]["mode"]
            if mode == "disconnect":
                return 0
            if mode not in {"success", "failure"}:
                return 4
            result = {"content": [], "isError": mode == "failure"}
        else:
            return 5
        print(json.dumps({"jsonrpc": "2.0", "id": identifier, "result": result}), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
