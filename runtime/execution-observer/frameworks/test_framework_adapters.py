from __future__ import annotations

import asyncio
import json
import logging
import operator
import os
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from typing import Annotated, TypedDict
from unittest.mock import patch

from autogen_core import AgentId, CancellationToken, MessageContext, RoutedAgent, SingleThreadedAgentRuntime
from autogen_core.tools import FunctionTool
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import StructuredTool
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, RetryPolicy, interrupt

from eap_execution import Journal, ObservationError
from eap_langgraph import LangGraphAdapter
from eap_autogen import AutoGenAdapter

# Test fixtures contain private markers; third-party diagnostic logging is not exported.
logging.disable(logging.CRITICAL)


class State(TypedDict):
    values: Annotated[list[int], operator.add]
    private: str


def starts(journal):
    return [e for e in journal.pending() if e["kind"] == "RUN_STARTED"]


def payload(journal):
    return dict(schemaVersion=1, producerId=journal.producer_id, framework=journal.framework,
                frameworkVersion=journal.version, events=journal.pending())


async def langgraph_scenario(journal):
    adapter = LangGraphAdapter(journal)
    async def calculate(value: int) -> int:
        """Calculate a deterministic public fixture."""
        await asyncio.sleep(0)
        return value + 1
    tool = StructuredTool.from_function(coroutine=adapter.tool("calculate", calculate))
    async def worker(state: State, config: RunnableConfig):
        assert config["configurable"]["fixture"] == 1
        assert state["private"] == "PRIVATE_STATE_MARKER"
        value = await tool.ainvoke({"value": 20})
        return {"values": [value]}
    graph = StateGraph(State)
    graph.add_node("one", adapter.agent_node("worker", worker))
    graph.add_node("two", adapter.agent_node("worker", worker))
    graph.add_edge(START, "one"); graph.add_edge(START, "two")
    graph.add_edge("one", END); graph.add_edge("two", END)
    with journal.agent("coordinator"):
        result = await graph.compile().ainvoke({"values": [], "private": "PRIVATE_STATE_MARKER"},
            {"configurable": {"fixture": 1}})
    assert sum(result["values"]) == 42
    return payload(journal)


class LangGraphTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.journal = Journal(Path(self.temp.name)/"events.sqlite", "eap-agents/framework-fixture",
                               framework="langgraph", version="1.2.17")
        self.adapter = LangGraphAdapter(self.journal)

    def tearDown(self):
        self.journal.close(); self.temp.cleanup()

    async def test_real_parallel_graph_and_structured_tools_keep_ownership(self):
        body = await langgraph_scenario(self.journal)
        runs = starts(self.journal)
        root = next(e for e in runs if "parentRunId" not in e)
        children = {e["runId"] for e in runs if e.get("parentRunId") == root["runId"]}
        self.assertEqual(len(children), 2)
        self.assertEqual({e["runId"] for e in body["events"] if e["kind"] == "TOOL_STARTED"}, children)
        self.assertEqual({e["processId"] for e in body["events"]}, {os.getpid()})
        self.assertNotIn("PRIVATE_STATE_MARKER", json.dumps(body))

    async def test_same_compiled_node_invoked_twice_has_distinct_executions(self):
        async def worker(state: State): return {"values": [1]}
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        app = graph.compile()
        for _ in range(2): await app.ainvoke({"values": [], "private": ""})
        self.assertEqual(len({e["runId"] for e in starts(self.journal)}), 2)
        self.assertIsNone(self.journal.current_or_none())

    async def test_sync_node_thread_executor_preserves_explicit_agent_parent(self):
        def calculate(value: int) -> int:
            """Return a deterministic fixture."""
            return value + 1
        tool = StructuredTool.from_function(self.adapter.tool("sync-tool", calculate))
        def worker(state: State): return {"values": [tool.invoke({"value": 41})]}
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        with self.journal.agent("coordinator") as root:
            result = await graph.compile().ainvoke({"values": [], "private": ""})
            self.assertEqual(self.journal.current(), root)
        self.assertEqual(result["values"], [42])
        self.assertEqual(starts(self.journal)[1]["parentRunId"], root.run_id)

    async def test_graph_cancellation_is_not_success(self):
        entered = asyncio.Event()
        async def worker(state: State):
            entered.set(); await asyncio.Event().wait()
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        task = asyncio.create_task(graph.compile().ainvoke({"values": [], "private": ""}))
        await asyncio.wait_for(entered.wait(), 5)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertEqual([e["outcome"] for e in self.journal.pending() if e["kind"] == "RUN_FINISHED"], ["CANCELLED"])

    async def test_tool_error_preserves_exception_and_omits_private_text(self):
        async def fail(value: int) -> int:
            """Fail a deterministic fixture."""
            raise ValueError("PRIVATE_TOOL_ERROR")
        tool = StructuredTool.from_function(coroutine=self.adapter.tool("failure", fail))
        async def worker(state: State): await tool.ainvoke({"value": 1})
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        with self.assertRaisesRegex(ValueError, "PRIVATE_TOOL_ERROR"):
            await graph.compile().ainvoke({"values": [], "private": ""})
        events = self.journal.pending()
        self.assertEqual([e["outcome"] for e in events if e["kind"].endswith("FINISHED")], ["FAILED", "FAILED"])
        self.assertNotIn("PRIVATE_TOOL_ERROR", json.dumps(events))

    def test_interrupt_leaves_incomplete_attempt_resume_creates_new_run(self):
        def worker(state: State):
            value = interrupt("PRIVATE_INTERRUPT_VALUE")
            return {"values": [value]}
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        app = graph.compile(checkpointer=InMemorySaver())
        config = {"configurable": {"thread_id": "fixture-only"}}
        app.invoke({"values": [], "private": ""}, config)
        first = starts(self.journal)[0]["runId"]
        self.assertFalse(any(e["kind"] == "RUN_FINISHED" for e in self.journal.pending()))
        result = app.invoke(Command(resume=42), config)
        self.assertEqual(result["values"], [42])
        self.assertEqual(len(starts(self.journal)), 2)
        self.assertFalse(any(e["kind"] == "RUN_FINISHED" and e["runId"] == first for e in self.journal.pending()))
        self.assertNotIn("PRIVATE_INTERRUPT_VALUE", json.dumps(self.journal.pending()))

    async def test_python310_async_interrupt_limit_is_present_without_adapter(self):
        import sys
        self.assertEqual(sys.version_info[:2], (3, 10), "This contract pins the remote Python version")
        async def worker(state: State):
            interrupt("PRIVATE_INTERRUPT_VALUE")
        for node in (worker, self.adapter.agent_node("worker", worker)):
            graph = StateGraph(State)
            graph.add_node("worker", node)
            graph.add_edge(START, "worker"); graph.add_edge("worker", END)
            app = graph.compile(checkpointer=InMemorySaver())
            with self.assertRaisesRegex(RuntimeError, "outside of a runnable context"):
                await app.ainvoke({"values": [], "private": ""}, {"configurable": {"thread_id": "fixture-only"}})
        self.assertEqual(self.journal.pending()[-1]["outcome"], "FAILED")

    def test_version_and_identity_mismatch_are_rejected(self):
        with patch("eap_framework_common.version", return_value="0.0.0"):
            with self.assertRaises(ValueError): LangGraphAdapter(self.journal)
        with self.assertRaises(ValueError): AutoGenAdapter(self.journal, SingleThreadedAgentRuntime())

    def test_generator_node_is_rejected_before_execution(self):
        def worker(state): yield state
        with self.assertRaises(ValueError): self.adapter.agent_node("stream", worker)

    async def test_framework_retry_records_distinct_failed_and_successful_attempts(self):
        calls = 0
        async def worker(state: State):
            nonlocal calls
            calls += 1
            if calls == 1: raise ValueError("PRIVATE_RETRY_ERROR")
            return {"values": [42]}
        graph = StateGraph(State)
        graph.add_node("worker", self.adapter.agent_node("worker", worker),
                       retry_policy=RetryPolicy(max_attempts=2, initial_interval=0, retry_on=(ValueError,)))
        graph.add_edge(START, "worker"); graph.add_edge("worker", END)
        result = await graph.compile().ainvoke({"values": [], "private": ""})
        self.assertEqual(result["values"], [42])
        self.assertEqual(len({e["runId"] for e in starts(self.journal)}), 2)
        self.assertEqual([e["outcome"] for e in self.journal.pending() if e["kind"] == "RUN_FINISHED"], ["FAILED", "SUCCEEDED"])

    async def test_nested_subgraph_and_deleted_temporary_code_keep_lineage(self):
        async def coder(state: State):
            path = Path(self.temp.name)/"temporary.py"
            path.write_bytes(b"result = 6 * 7\n")
            snapshot = self.journal.capture_code(path.read_bytes())
            path.unlink()
            with self.journal.tool("temporary-calculation", code=snapshot):
                result = {}
                exec(compile(snapshot.data, "<fixed-public-fixture>", "exec"), {}, result)
            return {"values": [result["result"]]}
        child = StateGraph(State)
        child.add_node("coder", self.adapter.agent_node("coder", coder))
        child.add_edge(START, "coder"); child.add_edge("coder", END)
        app = child.compile()
        async def parent(state: State): return await app.ainvoke(state)
        graph = StateGraph(State)
        graph.add_node("parent", self.adapter.agent_node("parent", parent))
        graph.add_edge(START, "parent"); graph.add_edge("parent", END)
        result = await graph.compile().ainvoke({"values": [], "private": ""})
        self.assertEqual(result["values"], [42])
        runs = starts(self.journal)
        self.assertEqual(runs[1]["parentRunId"], runs[0]["runId"])
        events = self.journal.pending()
        artifact = next(e for e in events if e["kind"] == "ARTIFACT_REGISTERED")
        tool = next(e for e in events if e["kind"] == "TOOL_STARTED")
        self.assertEqual(artifact["artifactDigest"], tool["artifactDigest"])
        self.assertEqual(artifact["runId"], runs[1]["runId"])
        self.assertFalse((Path(self.temp.name)/"temporary.py").exists())
        self.assertNotIn("result =", json.dumps(events))


@dataclass
class Work:
    value: int
    mode: str = "normal"
    private: str = "PRIVATE_MESSAGE_MARKER"


@dataclass
class Result:
    value: int


def worker_class(adapter, entered=None, release=None):
    async def calculate(value: int) -> int:
        return value + 1
    tool = FunctionTool(adapter.tool("calculate", calculate), description="deterministic fixture")
    class Worker(RoutedAgent):
        def __init__(self): super().__init__("deterministic fixture")

        @adapter.message_handler("worker")
        async def on_work(self, message: Work, ctx: MessageContext) -> Result:
            if entered is not None: entered.set()
            if message.mode == "delegate":
                return await adapter.send_message(Work(message.value), AgentId("worker", "child"), sender=self.id,
                                                  cancellation_token=ctx.cancellation_token)
            if message.mode == "block":
                wait = asyncio.create_task(release.wait())
                ctx.cancellation_token.link_future(wait)
                await wait
            if message.mode == "fail": raise ValueError("PRIVATE_HANDLER_ERROR")
            value = await tool.run_json({"value": message.value}, ctx.cancellation_token)
            return Result(value)
    return Worker


async def autogen_scenario(journal):
    runtime = SingleThreadedAgentRuntime()
    adapter = AutoGenAdapter(journal, runtime)
    Worker = worker_class(adapter)
    await Worker.register(runtime, "worker", Worker)
    runtime.start()
    try:
        with journal.agent("coordinator"):
            results = await asyncio.gather(*[adapter.send_message(Work(20, "delegate"), AgentId("worker", "shared")) for _ in range(2)])
        assert sum(r.value for r in results) == 42
        await runtime.stop_when_idle()
        assert adapter._pending == {}
    finally:
        await runtime.close()
    return payload(journal)


class AutoGenTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.journal = Journal(Path(self.temp.name)/"events.sqlite", "eap-agents/framework-fixture",
                               framework="autogen-core", version="0.7.5")
        self.runtime = SingleThreadedAgentRuntime()
        self.adapter = AutoGenAdapter(self.journal, self.runtime)
        self.started = False

    async def asyncTearDown(self):
        if self.started: await asyncio.wait_for(self.runtime.stop_when_idle(), 5)
        await self.runtime.close()
        self.journal.close(); self.temp.cleanup()

    async def register(self, entered=None, release=None):
        Worker = worker_class(self.adapter, entered, release)
        await Worker.register(self.runtime, "worker", Worker)
        self.runtime.start(); self.started = True

    async def test_real_runtime_nested_parallel_rpcs_preserve_parentage(self):
        body = await autogen_scenario(self.journal)
        runs = starts(self.journal)
        root = next(e for e in runs if "parentRunId" not in e)
        direct = {e["runId"] for e in runs if e.get("parentRunId") == root["runId"]}
        nested = {e["runId"] for e in runs if e.get("parentRunId") in direct}
        self.assertEqual((len(runs), len(direct), len(nested)), (5, 2, 2))
        self.assertEqual({e["runId"] for e in body["events"] if e["kind"] == "TOOL_STARTED"}, nested)
        self.assertEqual({e["processId"] for e in body["events"]}, {os.getpid()})
        self.assertNotIn("PRIVATE_MESSAGE_MARKER", json.dumps(body))

    async def test_runtime_startup_context_is_not_inferred_as_rpc_parent(self):
        with self.journal.agent("startup"):
            await self.register()
        result = await self.adapter.send_message(Work(41), AgentId("worker", "one"))
        self.assertEqual(result.value, 42)
        self.assertTrue(all("parentRunId" not in e for e in starts(self.journal)))
        self.assertEqual(len({e["traceId"] for e in starts(self.journal)}), 2)

    async def test_unbound_native_message_is_rejected_before_handler(self):
        entered = asyncio.Event()
        await self.register(entered)
        with self.assertRaises(ObservationError):
            await self.runtime.send_message(Work(1), AgentId("worker", "one"))
        self.assertFalse(entered.is_set())
        self.assertEqual(self.journal.pending(), [])

    async def test_cancellation_reaches_handler_and_cleans_binding(self):
        entered, release = asyncio.Event(), asyncio.Event()
        await self.register(entered, release)
        token = CancellationToken()
        task = asyncio.create_task(self.adapter.send_message(Work(1, "block"), AgentId("worker", "one"), cancellation_token=token))
        await asyncio.wait_for(entered.wait(), 5)
        token.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        await self.runtime.stop_when_idle(); self.started = False
        self.assertEqual(self.adapter._pending, {})
        self.assertEqual([e["outcome"] for e in self.journal.pending() if e["kind"] == "RUN_FINISHED"], ["CANCELLED"])

    async def test_handler_failure_preserves_exception_and_cleans_binding(self):
        await self.register()
        with self.assertRaisesRegex(ValueError, "PRIVATE_HANDLER_ERROR"):
            await self.adapter.send_message(Work(1, "fail"), AgentId("worker", "one"))
        self.assertEqual(self.adapter._pending, {})
        self.assertNotIn("PRIVATE_HANDLER_ERROR", json.dumps(self.journal.pending()))
        self.assertEqual(self.journal.pending()[-1]["outcome"], "FAILED")

    async def test_pending_bound_is_enforced_without_dispatching_extra_work(self):
        self.adapter.max_pending = 1
        entered, release = asyncio.Event(), asyncio.Event()
        await self.register(entered, release)
        first = asyncio.create_task(self.adapter.send_message(Work(1, "block"), AgentId("worker", "one")))
        await asyncio.wait_for(entered.wait(), 5)
        try:
            with self.assertRaises(ObservationError):
                await self.adapter.send_message(Work(1), AgentId("worker", "two"))
        finally:
            release.set(); await first
        self.assertEqual(len(starts(self.journal)), 1)
        self.assertEqual(self.adapter._pending, {})

    async def test_repeated_shared_agent_instance_has_distinct_execution_ids(self):
        await self.register()
        for _ in range(2): await self.adapter.send_message(Work(1), AgentId("worker", "same"))
        self.assertEqual(len({e["runId"] for e in starts(self.journal)}), 2)
        self.assertIsNone(self.journal.current_or_none())

    def test_unvalidated_version_and_runtime_are_rejected(self):
        with patch("eap_framework_common.version", return_value="0.0.0"):
            with self.assertRaises(ValueError): AutoGenAdapter(self.journal, self.runtime)
        with self.assertRaises(ValueError): AutoGenAdapter(self.journal, object())


if __name__ == "__main__":
    unittest.main()
