package com.eap.platform.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Approval context covers every enabled policy, including policies that did not match. */
final class GovernanceApprovalContext {
    private GovernanceApprovalContext() { }

    static String digest(ObjectMapper json, GovernanceDataProperties properties, List<GovernancePolicy> policies) {
        var context = json.createObjectNode().put("schema", 1)
                .put("enforcementEnabled", properties.isEnforcementEnabled())
                .put("failClosedOnSecret", properties.isFailClosedOnSecret());
        var entries = context.putArray("policies");
        policies.stream().sorted(Comparator.comparing(GovernancePolicy::getId)).forEach(policy -> {
            var entry = entries.addObject().put("id", policy.getId()).put("revision", policy.getRevision())
                    .put("name", policy.getName()).put("type", policy.getPolicyType().name())
                    .put("scopeType", policy.getScopeType()).put("scopeValue", policy.getScopeValue())
                    .put("mode", policy.getEnforcementMode().name()).put("enabled", policy.isEnabled());
            try {
                JsonNode rule = json.readTree(policy.getRuleJson());
                if (rule != null && rule.isTextual()) rule = json.readTree(rule.asText());
                entry.set("rule", rule == null ? json.nullNode() : ordered(json, rule));
            } catch (JsonProcessingException error) {
                // Preserve malformed legacy rules in the fingerprint; never silently omit them.
                entry.put("invalidRule", policy.getRuleJson());
            }
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(context).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | JsonProcessingException error) {
            throw new IllegalStateException("Approval context digest failed", error);
        }
    }

    private static JsonNode ordered(ObjectMapper json, JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, ordered(json, node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode(); node.forEach(value -> result.add(ordered(json, value)));
            return result;
        }
        return node;
    }
}
