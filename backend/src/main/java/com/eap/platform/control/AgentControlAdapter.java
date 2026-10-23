package com.eap.platform.control;

import com.eap.platform.registry.AgentRuntimeInstance;

import java.time.Instant;

public interface AgentControlAdapter {
    DispatchResult dispatch(AgentControlCommand command, AgentRuntimeInstance runtime);

    record DispatchResult(
            ControlCommandStatus status,
            String dispatchMode,
            String resultMessage,
            Integer httpStatus,
            Instant acknowledgedAt
    ) {
    }
}
