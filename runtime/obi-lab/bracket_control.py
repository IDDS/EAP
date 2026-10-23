"""Two ordinary-container positive controls around a separately dispatched EAP task."""
import json
import os
from pathlib import Path
import subprocess
import time

from scenario import readiness, run_batch


def main():
    out = Path("/work")
    child = subprocess.Popen(
        ["/usr/local/bin/python3", "/opt/eap-mcp/http_actor_server.py"],
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        close_fds=True, env={k: v for k, v in os.environ.items() if not k.startswith("EAP_")})
    try:
        ready = readiness(child.stdout)
        assert ready["port"] == 28761
        (out / "ready.json").write_text(json.dumps({"port": ready["port"], "childPid": child.pid}))
        for phase in ("before", "after"):
            deadline = time.monotonic() + 600
            while not (out / (phase + ".request")).exists():
                if time.monotonic() > deadline:
                    raise TimeoutError("bounded control wait expired")
                time.sleep(.1)
            request = json.loads((out / (phase + ".request")).read_text())
            assert request == {"batches": 10}
            durations = []
            for batch in range(10):
                durations.extend(run_batch(28761, batch))
            value = {"passed": True, "phase": phase, "httpRequests": 80, "toolCalls": 60,
                     "outcomes": {"SUCCEEDED": 20, "FAILED": 20, "UNKNOWN": 20},
                     "synthetic": True, "modelCalls": 0, "latencyMs": durations}
            with (out / (phase + ".json")).open("x") as f:
                json.dump(value, f)
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
