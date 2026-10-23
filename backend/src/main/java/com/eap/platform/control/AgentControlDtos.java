package com.eap.platform.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AgentControlDtos {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AgentControlDtos() {
    }

    public record Target(String instanceKey, String status, java.util.List<String> commands, String unavailableReason) {}
    public record Targets(java.util.List<Target> instances, boolean dispatchEnabled, int maxAttempts) {}

    public record CommandRequest(
            @NotNull Long agentId,
            @NotBlank @Size(max = 64) String commandType,
            @Size(max = 65536) String payloadJson,
            @Size(max = 128) String issuedBy,
            @Size(max = 128) String runtimeInstanceKey
    ) {
        public CommandRequest(Long agentId, String commandType, String payloadJson, String issuedBy) {
            this(agentId, commandType, payloadJson, issuedBy, null);
        }
    }

    public record CommandResponse(
            Long id,
            String commandKey,
            Long agentId,
            String commandType,
            String payloadJson,
            ControlCommandStatus status,
            String issuedBy,
            String dispatchMode,
            String runtimeInstanceKey,
            Integer attemptCount,
            Integer httpStatus,
            String resultMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant dispatchedAt,
            Instant acknowledgedAt
    ) {
    }

    static CommandResponse from(AgentControlCommand command) {
        return new CommandResponse(
                command.id,
                command.commandKey,
                command.agentId,
                command.commandType,
                normalizeJson(command.payloadJson),
                command.status,
                command.issuedBy,
                command.dispatchMode,
                command.runtimeInstanceKey,
                command.attemptCount,
                command.httpStatus,
                command.resultMessage,
                command.createdAt,
                command.updatedAt,
                command.dispatchedAt,
                command.acknowledgedAt
        );
    }

    private static String normalizeJson(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        try {
            return OBJECT_MAPPER.readValue(value, String.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }
}
