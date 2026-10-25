"""Opt-in AutoGen Core RPC adapter. Message content never enters the journal."""
import asyncio
import inspect
import uuid
from dataclasses import dataclass
from functools import wraps

from eap_execution import ObservationError, _slug
from eap_framework_common import preserve_annotations, require_framework, tool_callable

FRAMEWORK_VERSION = "0.7.5"


@dataclass
class _Delivery:
    parent: object
    recipient: object
    sender: object
    consumed: bool = False


class AutoGenAdapter:
    def __init__(self, journal, runtime, *, max_pending=128):
        require_framework(journal, "autogen-core", FRAMEWORK_VERSION)
        from autogen_core import SingleThreadedAgentRuntime
        if type(runtime) is not SingleThreadedAgentRuntime:
            raise ValueError("only the validated local runtime is supported")
        if type(max_pending) is not int or not 1 <= max_pending <= 1024:
            raise ValueError("invalid pending message capacity")
        self.journal, self.runtime, self.max_pending = journal, runtime, max_pending
        self._pending = {}
        self._loop = None

    def _check_loop(self):
        loop = asyncio.get_running_loop()
        if self._loop is None:
            self._loop = loop
        if self._loop is not loop:
            raise ObservationError("adapter cannot cross event loops")

    async def send_message(self, message, recipient, *, sender=None, cancellation_token=None):
        self._check_loop()
        if len(self._pending) >= self.max_pending:
            raise ObservationError("pending message capacity exhausted")
        identity = str(uuid.uuid4())
        # Parent comes only from the local observer context, never message fields or sender names.
        self._pending[identity] = _Delivery(self.journal.current_or_none(), recipient, sender)
        try:
            return await self.runtime.send_message(message, recipient, sender=sender,
                cancellation_token=cancellation_token, message_id=identity)
        finally:
            self._pending.pop(identity, None)

    def message_handler(self, definition_key):
        key = _slug(definition_key, 64)
        from autogen_core import message_handler

        def decorate(function):
            if not inspect.iscoroutinefunction(function):
                raise ValueError("message handlers must be async")

            @wraps(function)
            async def observed(agent, message, ctx):
                self._check_loop()
                delivery = self._pending.get(ctx.message_id)
                if (delivery is None or delivery.consumed or not ctx.is_rpc or ctx.topic_id is not None
                        or agent.id != delivery.recipient or ctx.sender != delivery.sender):
                    raise ObservationError("message lacks a matching local RPC observation binding")
                delivery.consumed = True
                # Queue dispatch may inherit unrelated runtime startup context; set an explicit parent.
                with self.journal.agent(key, parent=delivery.parent):
                    return await function(agent, message, ctx)

            return message_handler()(preserve_annotations(observed, function))
        return decorate

    def tool(self, definition_key, function):
        return tool_callable(self.journal, definition_key, function)
