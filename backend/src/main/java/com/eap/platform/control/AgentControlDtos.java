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

    public record CommandRequest(
            @NotNull Long agentId,
            @NotBlank @Size(max = 64) String commandType,
            String payloadJson,
            @Size(max = 128) String issuedBy
    ) {
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
            String resultMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant dispatchedAt
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
                command.resultMessage,
                command.createdAt,
                command.updatedAt,
                command.dispatchedAt
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
