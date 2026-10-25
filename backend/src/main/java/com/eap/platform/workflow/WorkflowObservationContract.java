package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Submission-time expectations, not model authorization or evidence of runtime behaviour. */
public final class WorkflowObservationContract {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private WorkflowObservationContract() {}

    public static int expectedModels(WorkflowDtos.NodeResponse node) {
        return expectedModels(node.configJson(), node.nodeType(), node.agentId());
    }

    public static int expectedModels(String raw, String type, Long agent) {
        try {
            var config = JSON.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (config == null || !config.isObject()) throw new IllegalArgumentException();
            var contract = config.get("eapObservation");
            if (contract == null) return 1; // Existing submissions retain the original default.
            if (agent == null || !Set.of("AGENT", "ARTIFACT").contains(type.toUpperCase(Locale.ROOT))
                    || config.has("eapTool")
                    || config.has("executionBackend") && !"RUNTIME".equals(config.path("executionBackend").asText()))
                throw new IllegalArgumentException();
            var names = new HashSet<String>(); contract.fieldNames().forEachRemaining(names::add);
            if (!contract.isObject() || !names.equals(Set.of("version", "modelEvents"))
                    || !"EAP_OBSERVATION_V1".equals(contract.path("version").asText())
                    || !contract.path("modelEvents").isTextual()) throw new IllegalArgumentException();
            return switch (contract.path("modelEvents").textValue()) {
                case "NONE" -> 0;
                case "ONE" -> 1;
                default -> throw new IllegalArgumentException();
            };
        } catch (Exception invalid) {
            throw new BusinessException("模型观测约定无效：仅支持绑定智能体的普通 Runtime 任务、EAP_OBSERVATION_V1 与 ONE/NONE；不适用于 AX 或内置工具任务");
        }
    }
}
