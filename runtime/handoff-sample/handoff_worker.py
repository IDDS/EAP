"""Opt-in fixed-program handoff, observed by the owning runtime after child exit.

The workflow carries a public fixture, not arbitrary executable code. A private
session reserves business execution once; later processes may only resend frozen
observations. Credentials are supplied on stdin and never persisted.
"""
import asyncio
import base64
import fcntl
import hashlib
import json
import logging
import math
import os
from pathlib import Path
import re
import sys
import tempfile
import uuid
from dataclasses import dataclass

from eap_execution import HttpTransport, Journal
from eap_links import ExecutionReference, LinkIntent, reference

VERSIONS = {"source": ("langgraph", "1.2.17"), "target": ("autogen-core", "0.7.5")}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def parse(raw):
    return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: None)


def exact(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys.split()):
        raise ValueError("invalid fields")


def identifier(value):
    if not isinstance(value, str) or str(uuid.UUID(value)) != value or int(uuid.UUID(value)) == 0:
        raise ValueError("invalid identity")
    return value


def program():
    return b"def calculate(numbers):\n    result = 0.0\n    result += numbers[0]\n    result += numbers[1]\n    result += numbers[2]\n    return result\n"


def atomic(path, value):
    fd, name = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as output:
            output.write(canonical(value)); output.flush(); os.fsync(output.fileno())
        os.replace(name, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def private_file(path, flags):
    fd = os.open(path, flags | os.O_NOFOLLOW, 0o600)
    stat = os.fstat(fd)
    if stat.st_uid != os.getuid() or stat.st_mode & 0o077 or not __import__('stat').S_ISREG(stat.st_mode):
        os.close(fd)
        raise ValueError("unsafe private file")
    return fd


def read_private(path):
    with os.fdopen(private_file(path, os.O_RDONLY)) as source:
        raw = source.read(32769)
    if len(raw) > 32768:
        raise ValueError("state limit")
    return parse(raw)


def validate_source(value, session):
    exact(value, "session source codeBase64 artifactDigest linkIds")
    if value["session"] != session:
        raise ValueError("foreign session")
    exact(value["source"], "streamKey runId producerId")
    ExecutionReference(value["source"]["streamKey"], value["source"]["runId"])
    identifier(value["source"]["producerId"])
    code = base64.b64decode(value["codeBase64"], validate=True)
    if code != program() or value["artifactDigest"] != hashlib.sha256(code).hexdigest():
        raise ValueError("only the fixed public program is supported")
    if not isinstance(value["linkIds"], list) or len(value["linkIds"]) != 2 or len(set(value["linkIds"])) != 2:
        raise ValueError("invalid links")
    for key in value["linkIds"]:
        identifier(key)
    return code


def validate_result(value, session):
    exact(value, "sum count handoff")
    if value["count"] != 3 or type(value["sum"]) not in (int, float) or not math.isfinite(value["sum"]):
        raise ValueError("invalid result")
    handoff = value["handoff"]
    exact(handoff, "generated target links")
    source = handoff["generated"]
    validate_source(source, session)
    exact(handoff["target"], "streamKey receiverRun executorRun producerId")
    target = handoff["target"]
    identifier(target["producerId"])
    for field in ("receiverRun", "executorRun"):
        ExecutionReference(target["streamKey"], target[field])
    if target["receiverRun"] == target["executorRun"] or target["producerId"] == source["source"]["producerId"]:
        raise ValueError("independent executions required")
    expected = intents(source, target)
    if handoff["links"] != [x.envelope() for x in expected]:
        raise ValueError("handoff differs")
    return handoff


def intents(source, target):
    origin = ExecutionReference(source["source"]["streamKey"], source["source"]["runId"])
    return [LinkIntent(source["linkIds"][0], origin, ExecutionReference(target["streamKey"], target["receiverRun"])),
            LinkIntent(source["linkIds"][1], origin, ExecutionReference(target["streamKey"], target["executorRun"]),
                       "CODE_HANDOFF", source["artifactDigest"])]


async def generate(journal):
    from langgraph.graph import END, START, StateGraph
    from eap_langgraph import LangGraphAdapter
    adapter = LangGraphAdapter(journal)
    captured = {}
    async def code_node(state: dict):
        with tempfile.TemporaryDirectory(prefix="eap-handoff-") as directory:
            path = Path(directory)/"sum.py"
            path.write_bytes(program())
            snapshot = journal.capture_code(path.read_bytes())
            path.unlink()
        captured.update(context=journal.current(), code=snapshot.data, digest=snapshot.digest)
        return {"generated": True}
    graph = StateGraph(dict)
    graph.add_node("generate", adapter.agent_node("temporary-generator", code_node))
    graph.add_edge(START, "generate"); graph.add_edge("generate", END)
    await graph.compile().ainvoke({})
    return captured


@dataclass
class Work:
    numbers: list
    code_base64: str


async def execute(journal, numbers, code):
    from autogen_core import AgentId, MessageContext, RoutedAgent, SingleThreadedAgentRuntime
    from eap_autogen import AutoGenAdapter
    runtime = SingleThreadedAgentRuntime()
    adapter = AutoGenAdapter(journal, runtime)
    captured = {}
    class Receiver(RoutedAgent):
        def __init__(self):
            super().__init__("handoff-receiver")
        @adapter.message_handler("handoff-receiver")
        async def handle(self, message: Work, ctx: MessageContext) -> float:
            captured["receiver"] = journal.current()
            with journal.agent("ephemeral-executor") as executor:
                captured["executor"] = executor
                code = base64.b64decode(message.code_base64, validate=True)
                if code != program():
                    raise ValueError("fixed message program differs")
                snapshot = journal.capture_code(code)
                with journal.tool("calculate_sum", code=snapshot):
                    scope = {"__builtins__": {}}
                    exec(compile(snapshot.data, "<eap-fixed-handoff>", "exec"), scope)
                    return scope["calculate"](message.numbers)
    await Receiver.register(runtime, "receiver", Receiver)
    runtime.start()
    try:
        captured["sum"] = await adapter.send_message(Work(numbers, base64.b64encode(code).decode()), AgentId("receiver", "one"))
        await runtime.stop_when_idle()
    finally:
        await runtime.close()
    return captured


async def business(payload, directory, transport):
    role = payload["role"]
    framework, version = VERSIONS[role]
    path = directory/"events.sqlite"
    os.close(private_file(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY))
    journal = Journal(path, payload["runtimeKey"], framework=framework, version=version, capacity=32)
    try:
        with journal.agent("handoff-coordinator", trace_id=payload["traceId"]):
            captured = await generate(journal) if role == "source" else await execute(journal, payload["numbers"], validate_source(payload["previous"], payload["session"]))
        delivery = journal.deliver(transport, max_attempts=3, max_batches=1)
        if journal.pending() or delivery["state"] != "ACKNOWLEDGED":
            raise ValueError("original events not acknowledged")
        journal.report_status(transport)
        if role == "source":
            origin = reference(journal, captured["context"])
            return {"session": payload["session"], "source": {"streamKey": origin.stream_key,
                    "runId": origin.run_id, "producerId": journal.producer_id},
                    "codeBase64": base64.b64encode(captured["code"]).decode(), "artifactDigest": captured["digest"],
                    "linkIds": [str(uuid.uuid4()), str(uuid.uuid4())]}
        receiver = reference(journal, captured["receiver"])
        executor = reference(journal, captured["executor"])
        target = {"streamKey": receiver.stream_key, "receiverRun": receiver.run_id,
                  "executorRun": executor.run_id, "producerId": journal.producer_id}
        return {"sum": captured["sum"], "count": len(payload["numbers"]), "handoff": {
            "generated": payload["previous"], "target": target,
            "links": [x.envelope() for x in intents(payload["previous"], target)]}}
    finally:
        journal.close()


def receipt_valid(receipt, body):
    exact(receipt, "linkId state duplicate executionPermissionGranted")
    if (receipt["linkId"] != body["linkId"] or receipt["state"] not in {"PENDING_CONFIRMATION", "CONFIRMED"}
            or body["side"] == "TARGET" and receipt["state"] != "CONFIRMED"
            or type(receipt["duplicate"]) is not bool or receipt["executionPermissionGranted"] is not False):
        raise ValueError("invalid link receipt")


async def process(payload, transport_factory=HttpTransport):
    exact(payload, "role action session taskKey traceId runtimeKey credential actorUid identityEpoch gateway directory numbers previous")
    role, action = payload["role"], payload["action"]
    allowed = {"source": {"generate", "offer", "resend-source"}, "target": {"execute", "status", "confirm", "resend-target"}}
    if role not in allowed or action not in allowed[role]:
        raise ValueError("unsupported phase")
    for field in ("session", "actorUid", "identityEpoch"):
        identifier(payload[field])
    if not isinstance(payload["taskKey"], str) or not re.fullmatch("wf-exec-"+payload["session"]+r":\d{1,6}", payload["taskKey"]):
        raise ValueError("invalid workflow task")
    if action in {"generate", "execute"} and not re.fullmatch(r"[a-f0-9]{32}", payload["traceId"]):
        raise ValueError("invalid trace")
    if action == "execute":
        nums = payload["numbers"]
        if not isinstance(nums, list) or len(nums) != 3 or any(type(n) not in (int, float) or not math.isfinite(n) or abs(n) > 1e12 for n in nums):
            raise ValueError("invalid fixed-shape numbers")
        validate_source(payload["previous"], payload["session"])
    elif payload["numbers"] is not None:
        raise ValueError("unexpected numbers")
    if action == "generate" and payload["previous"] is not None:
        raise ValueError("unexpected previous result")
    root = Path(payload["directory"])
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory = root/payload["session"]
    directory.mkdir(mode=0o700, exist_ok=True)
    for path in (root, directory):
        stat = path.lstat()
        if path.is_symlink() or stat.st_uid != os.getuid() or stat.st_mode & 0o077:
            raise ValueError("unsafe private directory")
    fd = private_file(directory/"lock", os.O_CREAT | os.O_RDWR)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        path = directory/"state.json"
        binding = {k: payload[k] for k in ("runtimeKey", "actorUid", "identityEpoch", "role", "session")}
        state = read_private(path) if path.exists() else None
        if state is not None and state["binding"] != binding:
            raise ValueError("runtime identity changed")
        transport = transport_factory(payload["gateway"], lambda: payload["credential"], allow_http=True, timeout=5)
        if action in {"generate", "execute"}:
            fingerprint = hashlib.sha256(canonical({k: payload[k] for k in ("taskKey", "numbers", "previous", "traceId")}).encode()).hexdigest()
            if state is not None:
                if state["fingerprint"] != fingerprint or state["phase"] != "COMPLETE":
                    raise ValueError("original execution unconfirmed or changed")
                return state["result"]
            state = {"binding": binding, "fingerprint": fingerprint, "taskKey": payload["taskKey"], "phase": "INTENT"}
            atomic(path, state)
            result = await business(payload, directory, transport)
            state.update(phase="COMPLETE", result=result)
            atomic(path, state)
            return result
        if state is None or state["phase"] != "COMPLETE":
            raise ValueError("no confirmed original execution")
        if action == "status":
            if payload["taskKey"] != state["taskKey"] or payload["previous"] != state["result"]["handoff"]["generated"]:
                raise ValueError("original result differs")
            return state["result"]
        handoff = validate_result(payload["previous"], payload["session"])
        if (role == "source" and state["result"] != handoff["generated"]
                or role == "target" and state["result"] != payload["previous"]):
            raise ValueError("original execution differs")
        framework, version = VERSIONS[role]
        own = handoff["generated"]["source"] if role == "source" else handoff["target"]
        bodies = [dict(schemaVersion=1, producerId=own["producerId"], framework=framework,
                       frameworkVersion=version, side=role.upper(), **link) for link in handoff["links"]]
        # Runtime-owned outbox: do not reopen the child's Journal to manufacture events or new SDK intents.
        if "outbox" not in state:
            if action.startswith("resend-"):
                raise ValueError("resend requires an original intent")
            state["outbox"] = bodies
            atomic(path, state)
        if state["outbox"] != bodies:
            raise ValueError("original intent differs")
        receipts = []
        for body in state["outbox"]:
            receipt = transport.report_link(payload["runtimeKey"], body)
            receipt_valid(receipt, body)
            receipts.append(receipt)
        state["lastReceipts"] = receipts
        atomic(path, state)
        return {"session": payload["session"], "side": role.upper(), "links": receipts,
                "businessReexecuted": False}
    finally:
        os.close(fd)


def main():
    logging.disable(logging.CRITICAL)
    try:
        raw = sys.stdin.buffer.read(60001)
        if len(raw) > 60000:
            raise ValueError("request limit")
        result = asyncio.run(process(parse(raw)))
        print(canonical(result))
        return 0
    except Exception:
        print('{"error":"HANDOFF_PHASE_UNCONFIRMED"}')
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
