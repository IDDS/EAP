package com.eap.platform.control;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControlServiceTests {
    @Test
    void dispatchesToLeastLoadedOnlineRuntimeAndPersistsAck() {
        AgentControlCommandRepository commandRepository = mock(AgentControlCommandRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentRuntimeInstanceRepository runtimeRepository = mock(AgentRuntimeInstanceRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ControlDispatchProperties properties = new ControlDispatchProperties();
        properties.setEnabled(true);
        AgentControlAdapter adapter = (command, runtime) -> new AgentControlAdapter.DispatchResult(
                ControlCommandStatus.ACKED, "HTTP_RUNTIME", "accepted", 202, Instant.now());
        Agent agent = new Agent();
        when(agentRepository.findById(7L)).thenReturn(Optional.of(agent));
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getInstanceKey()).thenReturn("runtime-7");
        when(runtimeRepository.findFirstByAgentIdAndStatusOrderByLoadScoreAsc(7L, RuntimeStatus.ONLINE))
                .thenReturn(Optional.of(runtime));
        when(commandRepository.save(any(AgentControlCommand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentControlService service = new AgentControlService(
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter);
        AgentControlDtos.CommandResponse response = service.issue(new AgentControlDtos.CommandRequest(
                7L, "pause", "{\"reason\":\"policy\"}", "operator"));

        assertThat(response.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(response.dispatchMode()).isEqualTo("HTTP_RUNTIME");
        assertThat(response.runtimeInstanceKey()).isEqualTo("runtime-7");
        assertThat(response.attemptCount()).isEqualTo(1);
        assertThat(response.httpStatus()).isEqualTo(202);
        assertThat(response.acknowledgedAt()).isNotNull();
    }

    @Test
    void keepsCommandInOutboxWhenAdapterIsDisabled() {
        AgentControlCommandRepository commandRepository = mock(AgentControlCommandRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentRuntimeInstanceRepository runtimeRepository = mock(AgentRuntimeInstanceRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ControlDispatchProperties properties = new ControlDispatchProperties();
        properties.setEnabled(false);
        AgentControlAdapter adapter = mock(AgentControlAdapter.class);
        when(agentRepository.findById(7L)).thenReturn(Optional.of(new Agent()));
        when(commandRepository.save(any(AgentControlCommand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentControlService service = new AgentControlService(
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter);
        AgentControlDtos.CommandResponse response = service.issue(new AgentControlDtos.CommandRequest(
                7L, "resume", null, null));

        assertThat(response.status()).isEqualTo(ControlCommandStatus.QUEUED);
        assertThat(response.dispatchMode()).isEqualTo("CONTROL_PLANE_OUTBOX");
        assertThat(response.resultMessage()).contains("未启用");
    }

    @Test
    void retriesFailedCommandWithIncrementedAttemptCount() {
        AgentControlCommandRepository commandRepository = mock(AgentControlCommandRepository.class);
        AgentRepository agentRepository = mock(AgentRepository.class);
        AgentRuntimeInstanceRepository runtimeRepository = mock(AgentRuntimeInstanceRepository.class);
        AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
        ControlDispatchProperties properties = new ControlDispatchProperties();
        properties.setEnabled(true);
        AgentControlAdapter adapter = (command, runtime) -> new AgentControlAdapter.DispatchResult(
                ControlCommandStatus.ACKED, "HTTP_RUNTIME", "accepted", 200, Instant.now());
        AgentControlCommand command = new AgentControlCommand();
        command.commandKey = "CTRL-retry";
        command.agentId = 7L;
        command.commandType = "PAUSE";
        command.status = ControlCommandStatus.FAILED;
        command.attemptCount = 1;
        command.payloadJson = "{}";
        command.issuedBy = "operator";
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getInstanceKey()).thenReturn("runtime-retry");
        when(commandRepository.findByCommandKey("CTRL-retry")).thenReturn(Optional.of(command));
        when(commandRepository.save(any(AgentControlCommand.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runtimeRepository.findFirstByAgentIdAndStatusOrderByLoadScoreAsc(7L, RuntimeStatus.ONLINE))
                .thenReturn(Optional.of(runtime));

        AgentControlService service = new AgentControlService(
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter);
        AgentControlDtos.CommandResponse response = service.retry("CTRL-retry");

        assertThat(response.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(response.attemptCount()).isEqualTo(2);
    }
}
