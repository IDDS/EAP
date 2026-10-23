"""Generate a cross-language ingestion fixture using three real stdio relays.

Run only in the remote validation environment. All values are synthetic.
"""
import json
from pathlib import Path
import subprocess
import sys
import tempfile


def generate():
    cases = []
    here = Path(__file__).parent
    for index, expected in enumerate(["SUCCEEDED", "FAILED", "UNKNOWN"], 1):
        with tempfile.TemporaryDirectory() as temp:
            server = Path(temp) / "server.py"
            result = {"jsonrpc": "2.0", "id": index, "result": {
                "content": [{"type": "text", "text": "PRIVATE_RESULT"}],
                "isError": expected == "FAILED"}}
            reply = b"" if expected == "UNKNOWN" else (json.dumps(result) + "\n").encode()
            server.write_text("import json,sys\n"
                              "request=json.loads(sys.stdin.buffer.readline())\n"
                              "assert request['method']=='tools/call'\n"
                              f"sys.stdout.buffer.write({reply!r});sys.stdout.buffer.flush()\n")
            events = Path(temp) / "events.jsonl"
            request = {"jsonrpc": "2.0", "id": index, "method": "tools/call",
                       "params": {"name": "calculate_sum", "arguments": {"private": "PRIVATE_INPUT"}}}
            child = subprocess.run([sys.executable, str(here / "relay.py"), "--server", "fixture",
                                    "--tool", "calculate_sum", "--trace-id", "a" * 32,
                                    "--parent-span-id", "b" * 16, "--agent-id", "75",
                                    "--events", str(events), "--", sys.executable, str(server)],
                                   input=(json.dumps(request) + "\n").encode(), capture_output=True, timeout=15)
            assert child.returncode == 0 and child.stdout == reply
            rows = [json.loads(line) for line in events.read_text().splitlines()]
            assert len(rows) == 1 and rows[0]["event"]["status"] == expected
            assert "PRIVATE" not in json.dumps(rows)
            cases.extend(rows)
    return cases


if __name__ == "__main__":
    print(json.dumps(generate(), indent=2))
