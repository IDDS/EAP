package com.eap.platform.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Public trace metadata is deliberately narrower than arbitrary producer attributes. */
public final class TraceMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> RUNTIME_DIAGNOSTICS = Map.of(
            "eap.runtime.stage", Set.of("START_OBSERVER", "WAIT_READY", "SEND_CONTROL", "READ_PROOF",
                    "WAIT_EXIT", "VALIDATE_PROOF", "VERIFY_EXPORT", "PERSIST_RECEIPT", "COMPLETED"),
            "eap.runtime.deadline_exceeded", Set.of("true", "false"),
            "eap.runtime.startup.version", Set.of("1"));
    private static final Set<String> STARTUP_TIMINGS = Set.of(
            "eap.runtime.startup.observer_import_ms", "eap.runtime.startup.identity_ready_ms",
            "eap.runtime.startup.exporter_start_ms", "eap.runtime.startup.server_spawn_ms",
            "eap.runtime.startup.server_ready_wait_ms", "eap.runtime.startup.observer_before_ready_ms",
            "eap.runtime.startup.server_import_ms", "eap.runtime.startup.server_bind_ms",
            "eap.runtime.startup.server_fqdn_ms", "eap.runtime.startup.server_before_ready_ms");
    private static final Set<String> IDENTIFIERS = Set.of(
            "agent.runtime", "eap.agent.id", "agent.id", "gen_ai.agent.id", "eap.runtime.instance",
            "eap.task.key", "ax.task.key", "eap.experiment.status", "eap.experiment.id",
            "eap.model.finish_reason", "eap.model.response_state", "eap.model.request.thinking_mode",
            "tool.name", "gen_ai.tool.name", "mcp.server.name", "rag.source", "retrieval.source",
            "a2a.peer.agent", "gen_ai.provider.name", "gen_ai.request.model", "gen_ai.response.model",
            "gen_ai.operation.name", "gen_ai.system", "service.name", "service.version");
    private static final Set<String> COUNTS = Set.of(
            "eap.model.request.generation_limit", "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens",
            "gen_ai.usage.cache_read.input_tokens", "gen_ai.usage.reasoning.output_tokens",
            "gen_ai.request.max_tokens", "http.response.status_code",
            "eap.runtime.observer_start_ms", "eap.runtime.ready_wait_ms", "eap.runtime.control_write_ms",
            "eap.runtime.proof_read_ms", "eap.runtime.exit_wait_ms");
    private TraceMetadata() { }
    public static String from(Map<String, JsonNode> attributes) {
        Map<String, String> safe = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (value == null || !value.isValueNode() || value.isNull()) return;
            String text = value.asText();
            if (RUNTIME_DIAGNOSTICS.containsKey(key)) {
                if (value.isTextual() && RUNTIME_DIAGNOSTICS.get(key).contains(text)) safe.put(key, text);
            } else if (STARTUP_TIMINGS.contains(key)) {
                if ((value.isTextual() || value.isIntegralNumber()) && text.matches("[0-9]{1,5}")
                        && Integer.parseInt(text) <= 60000) safe.put(key, text);
            } else if (McpProtocolObservation.VALUES.containsKey(key)) {
                if (value.isTextual() && McpProtocolObservation.VALUES.get(key).contains(text)) safe.put(key, text);
            } else if (IDENTIFIERS.contains(key) && text.matches("[A-Za-z0-9_.:/@-]{1,256}")) safe.put(key, text);
            else if (COUNTS.contains(key) && text.matches("[0-9]{1,15}")) safe.put(key, text);
        });
        try { return JSON.writeValueAsString(safe); } catch (Exception ignored) { return "{}"; }
    }
    /** Applied on reads too: old stored attributes must not bypass the new projection. */
    public static String project(String stored) {
        try {
            JsonNode node = JSON.readTree(stored);
            if (node == null || !node.isObject()) return "{}";
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
            return from(fields);
        } catch (Exception ignored) { return "{}"; }
    }
    public static String reference(String value) {
        if (value.matches("sha256:[a-f0-9]{64}")) return value;
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
