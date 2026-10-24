"""Lifecycle wrappers for explicitly declared LangGraph Agent nodes."""
from eap_execution import _slug
from eap_framework_common import observed_callable, require_framework, tool_callable

FRAMEWORK_VERSION = "1.2.17"


class LangGraphAdapter:
    def __init__(self, journal):
        require_framework(journal, "langgraph", FRAMEWORK_VERSION)
        from langgraph.errors import GraphBubbleUp
        self.journal = journal
        self.control_flow = (GraphBubbleUp,)

    def agent_node(self, definition_key, function):
        key = _slug(definition_key, 64)
        return observed_callable(function, lambda: self.journal.agent(key, incomplete_on=self.control_flow))

    def tool(self, definition_key, function):
        return tool_callable(self.journal, definition_key, function)
