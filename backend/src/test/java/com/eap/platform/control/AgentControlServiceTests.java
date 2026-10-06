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
        when(runtimeRepository.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(RuntimeStatus.ONLINE), any(Instant.class)))
                .thenReturn(Optional.of(runtime));
        when(commandRepository.save(any(AgentControlCommand.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentControlService service = new AgentControlService(
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter, mock(AgentControlAdapter.class), new com.eap.platform.registry.RuntimeLeaseProperties());
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
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter, mock(AgentControlAdapter.class), new com.eap.platform.registry.RuntimeLeaseProperties());
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
        when(agentRepository.findById(7L)).thenReturn(Optional.of(new Agent()));
        when(commandRepository.findForRetry("CTRL-retry")).thenReturn(Optional.of(command));
        when(commandRepository.save(any(AgentControlCommand.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runtimeRepository.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(RuntimeStatus.ONLINE), any(Instant.class)))
                .thenReturn(Optional.of(runtime));

        AgentControlService service = new AgentControlService(
                commandRepository, agentRepository, runtimeRepository, auditLogRepository, properties, adapter, mock(AgentControlAdapter.class), new com.eap.platform.registry.RuntimeLeaseProperties());
        AgentControlDtos.CommandResponse response = service.retry("CTRL-retry", "retry-operator");

        assertThat(response.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(response.attemptCount()).isEqualTo(2);
    }
    @Test
    void offlineRuntimeRetriesStopAtConfiguredLimit() {
        var commands = mock(AgentControlCommandRepository.class);
        var runtimes = mock(AgentRuntimeInstanceRepository.class);
        var properties = new ControlDispatchProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(3);
        var command = new AgentControlCommand();
        command.commandKey = "CTRL-offline";
        command.agentId = 7L;
        command.commandType = "PAUSE";
        command.status = ControlCommandStatus.FAILED;
        command.attemptCount = 1;
        command.issuedBy = "operator";
        when(commands.findForRetry(command.commandKey)).thenReturn(Optional.of(command));
        when(commands.save(any(AgentControlCommand.class))).thenAnswer(call -> call.getArgument(0));
        when(runtimes.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(RuntimeStatus.ONLINE), any(Instant.class)))
                .thenReturn(Optional.empty());
        var agents = mock(AgentRepository.class);
        when(agents.findById(7L)).thenReturn(Optional.of(new Agent()));
        var service = new AgentControlService(commands, agents, runtimes,
                mock(AuditLogRepository.class), properties, mock(AgentControlAdapter.class), mock(AgentControlAdapter.class), new com.eap.platform.registry.RuntimeLeaseProperties());

        assertThat(service.retry(command.commandKey, "retry-operator").attemptCount()).isEqualTo(2);
        assertThat(service.retry(command.commandKey, "retry-operator").attemptCount()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.retry(command.commandKey, "retry-operator"))
                .isInstanceOf(com.eap.platform.common.BusinessException.class)
                .hasMessageContaining("最大重试次数");
        assertThat(command.status).isEqualTo(ControlCommandStatus.FAILED);
    }

    @Test
    void routesSuspendedSubstrateActorWithoutHeartbeatToSubstrateAdapter() {
        var commands = mock(AgentControlCommandRepository.class);
        var agents = mock(AgentRepository.class);
        var runtimes = mock(AgentRuntimeInstanceRepository.class);
        var properties = new ControlDispatchProperties();
        properties.setEnabled(true);
        when(agents.findById(7L)).thenReturn(Optional.of(new Agent()));
        when(commands.save(any(AgentControlCommand.class))).thenAnswer(call -> call.getArgument(0));
        when(runtimes.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(RuntimeStatus.ONLINE), any(Instant.class)))
                .thenReturn(Optional.empty());
        AgentRuntimeInstance actor = mock(AgentRuntimeInstance.class);
        when(actor.getProtocol()).thenReturn("SUBSTRATE");
        when(actor.getStatus()).thenReturn(RuntimeStatus.ONLINE);
        when(actor.getLoadScore()).thenReturn(0);
        when(actor.getInstanceKey()).thenReturn("eap-demo/session-1");
        when(runtimes.findByAgentId(7L)).thenReturn(java.util.List.of(actor));
        AgentControlAdapter http = mock(AgentControlAdapter.class);
        AgentControlAdapter substrate = (command, runtime) -> new AgentControlAdapter.DispatchResult(
                ControlCommandStatus.ACKED, "SUBSTRATE_RUNTIME", "RUNNING", null, Instant.now());
        var service = new AgentControlService(commands, agents, runtimes, mock(AuditLogRepository.class), properties,
                http, substrate, new com.eap.platform.registry.RuntimeLeaseProperties());

        var response = service.issue(new AgentControlDtos.CommandRequest(7L, "resume", null, "operator"));

        assertThat(response.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(response.dispatchMode()).isEqualTo("SUBSTRATE_RUNTIME");
        assertThat(response.runtimeInstanceKey()).isEqualTo("eap-demo/session-1");
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    @Test
    void revertIsRejectedForHttpRuntimes() {
        var commands = mock(AgentControlCommandRepository.class);
        var agents = mock(AgentRepository.class);
        var runtimes = mock(AgentRuntimeInstanceRepository.class);
        var properties = new ControlDispatchProperties();
        properties.setEnabled(true);
        when(agents.findById(7L)).thenReturn(Optional.of(new Agent()));
        when(commands.save(any(AgentControlCommand.class))).thenAnswer(call -> call.getArgument(0));
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn("HTTP");
        when(runtimes.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(RuntimeStatus.ONLINE), any(Instant.class)))
                .thenReturn(Optional.of(runtime));
        AgentControlAdapter http = mock(AgentControlAdapter.class);
        var service = new AgentControlService(commands, agents, runtimes, mock(AuditLogRepository.class), properties,
                http, mock(AgentControlAdapter.class), new com.eap.platform.registry.RuntimeLeaseProperties());

        var response = service.issue(new AgentControlDtos.CommandRequest(7L, "revert", null, "operator"));

        assertThat(response.status()).isEqualTo(ControlCommandStatus.FAILED);
        assertThat(response.resultMessage()).contains("仅适用于 Substrate");
        org.mockito.Mockito.verifyNoInteractions(http);
    }
}
