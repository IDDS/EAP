package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** EAP's bounded JSON shape contract, not a general JSON Schema implementation. */
public final class WorkflowOutputContract {
    private static final String VERSION = "EAP_JSON_V1";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Contract(String version, String source, JsonNode schema, String sha256) {}
    /** No output, schema, property names, or rejected values in console-visible metadata. */
    public record Validation(String contract, String source, String contractSha256, String status, String code) {}
    public record Result(Validation validation, JsonNode accepted) {}

    private WorkflowOutputContract() {}

    public static Contract read(WorkflowDtos.NodeResponse node) {
        return read(node.configJson(), node.nodeType(), node.agentId());
    }

    public static Contract read(String raw, String nodeType, Long agentId) {
        try {
            var config = JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            var contract = config.get("eapOutput");
            if (contract == null) return null;
            require(Set.of("AGENT", "ARTIFACT").contains(nodeType.toUpperCase(Locale.ROOT)) && agentId != null);
            return definition(contract, config.has("executionBackend") ? config.path("executionBackend").asText() : "RUNTIME");
        } catch (Exception ex) {
            throw new BusinessException("结果契约无效：仅支持常规任务的 EAP_JSON_V1、OUTPUT/JSON_TEXT 及有界 JSON 结构；请核对支持的字段与限制");
        }
    }

    /** Plan steps may be configured before member assignment; execution still requires a bound agent. */
    public static String validateDefinition(String raw, String backend) {
        if (raw == null) return null;
        try {
            require(raw.getBytes(StandardCharsets.UTF_8).length <= 8192);
            JsonNode value = JSON.readTree(raw);
            definition(value, backend == null ? "RUNTIME" : backend);
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("结果契约无效：仅支持常规任务的 EAP_JSON_V1、OUTPUT/JSON_TEXT 及有界 JSON 结构；请核对支持的字段与限制");
        }
    }

    private static Contract definition(JsonNode contract, String backend) throws Exception {
        require("RUNTIME".equals(backend));
        require(contract.isObject() && names(contract).equals(Set.of("version", "source", "schema")));
        require(VERSION.equals(contract.path("version").asText()));
        String source = contract.path("source").asText();
        require(Set.of("OUTPUT", "JSON_TEXT").contains(source));
        var schema = contract.get("schema");
        require(JSON.writeValueAsBytes(contract).length <= 8192 && "object".equals(schema.path("type").asText()));
        schema(schema, 1, new int[]{0});
        return new Contract(VERSION, source, schema, digest(contract));
    }

    public static Result check(Contract contract, String receiptStatus, JsonNode output) {
        if (contract == null) return new Result(null, output);
        if (!"SUCCEEDED".equals(receiptStatus)) return result(contract, "NOT_EVALUATED", "RECEIPT_NOT_SUCCEEDED", null);
        if (output == null || output.isNull()) return result(contract, "REJECTED", "OUTPUT_MISSING", null);
        JsonNode value = output;
        if ("JSON_TEXT".equals(contract.source())) {
            if (!output.isTextual()) return result(contract, "REJECTED", "TEXT_REQUIRED", null);
            try { value = JSON.readTree(output.textValue()); }
            catch (Exception ex) { return result(contract, "REJECTED", "INVALID_JSON", null); }
            if (value == null || value.isMissingNode()) return result(contract, "REJECTED", "INVALID_JSON", null);
        }
        String error = validate(contract.schema(), value);
        return error == null ? result(contract, "PASSED", "VALID", value) : result(contract, "REJECTED", error, null);
    }

    private static Result result(Contract contract, String state, String code, JsonNode accepted) {
        return new Result(new Validation(contract.version(), contract.source(), contract.sha256(), state, code), accepted);
    }

    private static void schema(JsonNode s, int depth, int[] count) {
        require(depth <= 8 && ++count[0] <= 128 && s != null && s.isObject() && s.path("type").isTextual());
        String type = s.path("type").asText();
        switch (type) {
            case "object" -> {
                require(names(s).equals(Set.of("type", "properties", "required", "additionalProperties")));
                var props = s.get("properties"); var required = s.get("required");
                require(props.isObject() && props.size() <= 64 && required.isArray() && required.size() <= props.size()
                        && s.get("additionalProperties").isBoolean());
                Set<String> seen = new HashSet<>();
                for (var key : required) require(key.isTextual() && props.has(key.textValue()) && seen.add(key.textValue()));
                props.fields().forEachRemaining(entry -> {
                    require(!entry.getKey().isEmpty() && entry.getKey().length() <= 128);
                    schema(entry.getValue(), depth + 1, count);
                });
            }
            case "array" -> { require(names(s).equals(Set.of("type", "items"))); schema(s.get("items"), depth + 1, count); }
            case "string", "number", "integer", "boolean", "null" -> require(names(s).equals(Set.of("type")));
            default -> throw new IllegalArgumentException();
        }
    }

    private static String validate(JsonNode s, JsonNode v) {
        String type = s.path("type").asText();
        boolean matches = v != null && switch (type) {
            case "object" -> v.isObject(); case "array" -> v.isArray(); case "string" -> v.isTextual();
            case "number" -> v.isNumber() && Double.isFinite(v.doubleValue());
            case "integer" -> v.isIntegralNumber();
            case "boolean" -> v.isBoolean(); case "null" -> v.isNull(); default -> false;
        };
        if (!matches) return "TYPE_MISMATCH";
        if ("object".equals(type)) {
            for (var key : s.get("required")) if (!v.has(key.textValue())) return "REQUIRED_MISSING";
            var props = s.get("properties");
            for (var it = v.fields(); it.hasNext();) {
                var entry = it.next(); var child = props.get(entry.getKey());
                if (child == null) { if (!s.get("additionalProperties").booleanValue()) return "UNEXPECTED_PROPERTY"; }
                else { String error = validate(child, entry.getValue()); if (error != null) return error; }
            }
        } else if ("array".equals(type)) {
            for (var item : v) { String error = validate(s.get("items"), item); if (error != null) return error; }
        }
        return null;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new HashSet<>(); node.fieldNames().forEachRemaining(result::add); return result;
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
    private static String digest(JsonNode value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    }
}
