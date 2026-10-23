package com.eap.platform.control;

import com.eap.platform.agent.*;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.registry.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ControlTargetsTests {
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentRuntimeInstanceRepository runtimes = mock(AgentRuntimeInstanceRepository.class);
    private final AgentControlCommandRepository commands = mock(AgentControlCommandRepository.class);
    private final AgentControlAdapter http = mock(AgentControlAdapter.class);
    private final AgentControlAdapter substrate = mock(AgentControlAdapter.class);
    private final ControlDispatchProperties properties = new ControlDispatchProperties();
    private final AgentControlService service = new AgentControlService(commands, agents, runtimes,
            mock(AuditLogRepository.class), properties, http, substrate, new RuntimeLeaseProperties());
    private AgentRuntimeInstance instance(String key, String protocol, Instant heartbeat) {
        var r = mock(AgentRuntimeInstance.class);
        when(r.getInstanceKey()).thenReturn(key); when(r.getAgentId()).thenReturn(7L);
        when(r.getProtocol()).thenReturn(protocol); when(r.getStatus()).thenReturn(RuntimeStatus.ONLINE);
        when(r.getLastHeartbeatAt()).thenReturn(heartbeat);return r;
    }
    @Test void targetCapabilitiesRespectProtocolLeaseAndImportedOwnership() {
        when(agents.findById(7L)).thenReturn(Optional.of(new Agent()));
        var nativeActor=instance("native", "SUBSTRATE", null);
        var imported=instance("imported", "SUBSTRATE", null);when(imported.getImportedActorUid()).thenReturn("actor-uid");
        var stale=instance("stale", "HTTP", Instant.now().minusSeconds(3600));
        var live=instance("live", "HTTP", Instant.now());
        var unsupported=instance("other", "GRPC", Instant.now());
        when(runtimes.findByAgentId(7L)).thenReturn(List.of(nativeActor,imported,stale,live,unsupported));
        var response=service.targets(7L).instances();
        assertThat(response.get(0).commands()).contains("REVERT").doesNotContain("UPDATE_CONFIG");
        assertThat(response.get(1).commands()).containsExactly("RUN_HEALTH_CHECK","PAUSE","RESUME");
        assertThat(response.get(2).commands()).isEmpty();assertThat(response.get(2).unavailableReason()).contains("心跳");
        assertThat(response.get(3).commands()).contains("UPDATE_CONFIG").doesNotContain("REVERT");
        assertThat(response.get(4).commands()).isEmpty();
    }
    @Test void retiredAgentHasNoAvailableCommands() {
        var agent=new Agent();agent.setLifecycleStatus(AgentLifecycleStatus.RETIRED);
        when(agents.findById(7L)).thenReturn(Optional.of(agent));
        var live=instance("live","HTTP",Instant.now());
        when(runtimes.findByAgentId(7L)).thenReturn(List.of(live));
        assertThat(service.targets(7L).instances().get(0).commands()).isEmpty();
    }
    @Test void explicitlySelectedExpiredRuntimeCannotDispatchOrFallback() {
        properties.setEnabled(true);
        when(agents.findById(7L)).thenReturn(Optional.of(new Agent()));
        when(commands.save(any(AgentControlCommand.class))).thenAnswer(call->call.getArgument(0));
        var stale=instance("stale","HTTP",Instant.now().minusSeconds(3600));
        when(runtimes.findByInstanceKey("stale")).thenReturn(Optional.of(stale));
        var response=service.issue(new AgentControlDtos.CommandRequest(7L,"RUN_HEALTH_CHECK","{}","operator","stale"));
        assertThat(response.status()).isEqualTo(ControlCommandStatus.FAILED);
        verifyNoInteractions(http,substrate);
        verify(runtimes,never()).findByAgentId(any());
    }
}
