package com.eap.platform.observability;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/** Passive protocol evidence only; never an execution receipt or authorization. */
final class McpProtocolObservation {
    static final String PREFIX = "eap.observation.";
    static final Map<String, Set<String>> VALUES = Map.of(
            PREFIX + "contract", Set.of("MCP_PROTOCOL_V1"),
            PREFIX + "outcome", Set.of("SUCCEEDED", "FAILED", "UNKNOWN"),
            PREFIX + "reason", Set.of("TOOL_RESULT", "TOOL_ERROR", "RPC_ERROR", "INVALID_RESPONSE",
                    "STREAM_CLOSED", "CANCEL_REQUESTED", "CANCEL_BREAKS_CORRELATION", "FRAME_TOO_LARGE",
                    "INVALID_FRAME", "UNSUPPORTED_FRAME", "AMBIGUOUS_OR_EXHAUSTED_REQUEST_IDS",
                    "INVALID_TOOL_REQUEST", "AMBIGUOUS_REQUEST_ID", "PENDING_LIMIT"),
            PREFIX + "evidence", Set.of("PROTOCOL_RESPONSE", "NO_CONFIRMED_RESPONSE"),
            PREFIX + "task_correlation", Set.of("CONFIGURED", "UNAVAILABLE"),
            PREFIX + "effect", Set.of("UNVERIFIED"));

    private McpProtocolObservation() { }

    static boolean applies(TraceSpanKind kind, Map<String, JsonNode> attrs) {
        return kind == TraceSpanKind.MCP && attrs.keySet().stream().anyMatch(k -> k.startsWith(PREFIX));
    }

    static String status(JsonNode span, Map<String, JsonNode> attrs) {
        // Missing, conflicting and unsupported evidence must not use OTLP's
        // legacy default-to-success behavior. Producer claims remain passive.
        if (!"MCP_PROTOCOL_V1".equals(text(attrs, PREFIX + "contract"))
                || !"tools/call".equals(text(attrs, "rpc.method"))
                || !"UNVERIFIED".equals(text(attrs, PREFIX + "effect"))) return "UNKNOWN";
        String outcome = text(attrs, PREFIX + "outcome");
        String reason = text(attrs, PREFIX + "reason");
        String evidence = text(attrs, PREFIX + "evidence");
        String code = span.path("status").path("code").asText("");
        if ("SUCCEEDED".equals(outcome) && "TOOL_RESULT".equals(reason)
                && "PROTOCOL_RESPONSE".equals(evidence)
                && Set.of("1", "STATUS_CODE_OK").contains(code)) return "SUCCEEDED";
        if ("FAILED".equals(outcome) && ("TOOL_ERROR".equals(reason) || "RPC_ERROR".equals(reason))
                && "PROTOCOL_RESPONSE".equals(evidence)
                && Set.of("2", "STATUS_CODE_ERROR").contains(code)) return "FAILED";
        return "UNKNOWN";
    }

    private static String text(Map<String, JsonNode> attrs, String name) {
        JsonNode node = attrs.get(name);
        return node != null && node.isTextual() ? node.textValue() : null;
    }
}
