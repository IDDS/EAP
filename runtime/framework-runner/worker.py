"""Fixed, governed sum demonstration. No task-supplied code, paths, models or URLs."""
import asyncio
import json
import logging
import math
import os
import re
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import TypedDict

from eap_execution import HttpTransport, Journal

VERSIONS = {"langgraph": "1.2.17", "autogen-core": "0.7.5"}
CODE = b"def calculate(numbers):\n    result = 0.0\n    for number in numbers:\n        result += number\n    return result\n"


class SumState(TypedDict):
    numbers: list[float]
    result: float


@dataclass
class Work:
    numbers: list[float]


def validate_numbers(numbers):
    if not isinstance(numbers, list) or not 1 <= len(numbers) <= 100 or any(
            type(n) not in (int, float) or not math.isfinite(n) or abs(n) > 1e12 for n in numbers):
        raise ValueError("invalid numbers")
    return numbers


def temporary_tool(journal, numbers):
    # Execute the exact captured bytes; the path is private, generated here and removed before export.
    with tempfile.TemporaryDirectory(prefix="eap-fixed-sum-") as directory:
        path = Path(directory)/"sum.py"
        path.write_bytes(CODE)
        snapshot = journal.capture_code(path.read_bytes(), language="PYTHON")
        with journal.tool("calculate_sum", code=snapshot):
            scope = {"__builtins__": {}}
            exec(compile(snapshot.data, "<eap-fixed-sum>", "exec"), scope)
            return scope["calculate"](numbers)


async def langgraph_sum(journal, numbers):
    from langgraph.graph import END, START, StateGraph
    from eap_langgraph import LangGraphAdapter
    adapter = LangGraphAdapter(journal)
    async def validate(state: SumState):
        return {"numbers": validate_numbers(state["numbers"])}
    async def coder(state: SumState):
        return {"result": temporary_tool(journal, state["numbers"])}
    graph = StateGraph(SumState)
    graph.add_node("validate", adapter.agent_node("validator", validate))
    graph.add_node("code", adapter.agent_node("temporary-coder", coder))
    graph.add_edge(START, "validate"); graph.add_edge("validate", "code"); graph.add_edge("code", END)
    result = await graph.compile().ainvoke({"numbers": numbers})
    return result["result"]


async def autogen_sum(journal, numbers):
    from autogen_core import AgentId, MessageContext, RoutedAgent, SingleThreadedAgentRuntime
    from eap_autogen import AutoGenAdapter
    runtime = SingleThreadedAgentRuntime()
    adapter = AutoGenAdapter(journal, runtime)
    class Validator(RoutedAgent):
        def __init__(self): super().__init__("validator")
        @adapter.message_handler("validator")
        async def handle(self, message: Work, ctx: MessageContext) -> Work:
            return Work(validate_numbers(message.numbers))
    class Coder(RoutedAgent):
        def __init__(self): super().__init__("temporary-coder")
        @adapter.message_handler("temporary-coder")
        async def handle(self, message: Work, ctx: MessageContext) -> float:
            return temporary_tool(journal, message.numbers)
    await Validator.register(runtime, "validator", Validator)
    await Coder.register(runtime, "coder", Coder)
    runtime.start()
    try:
        checked = await adapter.send_message(Work(numbers), AgentId("validator", "one"))
        result = await adapter.send_message(checked, AgentId("coder", "one"))
        await runtime.stop_when_idle()
        return result
    finally:
        await runtime.close()


async def execute(payload, transport_factory=HttpTransport):
    required = {"framework", "numbers", "callId", "traceId", "runtimeKey", "credential", "gateway", "directory"}
    if set(payload) != required or payload["framework"] not in VERSIONS:
        raise ValueError("invalid framework request")
    framework = payload["framework"]
    validate_numbers(payload["numbers"])
    if str(uuid.UUID(payload["callId"])) != payload["callId"] or not re.fullmatch(r"[a-f0-9]{32}", payload["traceId"]):
        raise ValueError("invalid execution identity")
    directory = Path(payload["directory"])
    if not directory.is_dir() or directory.stat().st_mode & 0o077:
        raise ValueError("private observation directory required")
    path = directory/(payload["callId"]+".sqlite")
    # Atomic reservation: no process may re-execute an uncertain prior invocation.
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600); os.close(fd)
    journal = Journal(path, payload["runtimeKey"], framework=framework, version=VERSIONS[framework], capacity=64)
    try:
        with journal.agent("coordinator", trace_id=payload["traceId"]):
            result = await (langgraph_sum if framework == "langgraph" else autogen_sum)(journal, payload["numbers"])
        count = len(journal.pending())
        stream = ""
        delivery = {"state": "UNAVAILABLE", "attempts": 0}
        try:
            transport = transport_factory(payload["gateway"], lambda: payload["credential"], allow_http=True, timeout=5)
            delivery = journal.deliver(transport, max_attempts=3, max_batches=1)
            stream = delivery["streamKey"]
        except Exception:
            pass  # Delivery failure never re-executes the framework or erases its successful result.
        return {"sum": result, "count": len(payload["numbers"]), "observation": {
            "framework": framework, "producerId": journal.producer_id, "streamKey": stream,
            "events": count, "pending": len(journal.pending()),
            "deliveryState": delivery["state"], "deliveryAttempts": delivery["attempts"]}}
    finally:
        journal.close()


def main():
    logging.disable(logging.CRITICAL)
    try:
        raw = sys.stdin.buffer.read(16385)
        if len(raw) > 16384: raise ValueError("request too large")
        result = asyncio.run(execute(json.loads(raw)))
        sys.stdout.write(json.dumps(result, allow_nan=False)+"\n")
    except Exception:
        # Never emit exception text, input, environment, traceback or credential material.
        sys.stdout.write('{"error":"FRAMEWORK_EXECUTION_UNCONFIRMED"}\n')
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
