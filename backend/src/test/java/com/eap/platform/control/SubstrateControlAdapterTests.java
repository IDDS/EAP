package com.eap.platform.control;

import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubstrateControlAdapterTests {
    private final SubstrateClient client = mock(SubstrateClient.class);
    private final SubstrateControlAdapter adapter = new SubstrateControlAdapter(client);
    private final SubstrateActorRef actor = new SubstrateActorRef("eap-demo", "session-1");

    @Test
    void pauseSuspendsActorAndReportsItsState() {
        when(client.suspend(actor)).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));

        var result = adapter.dispatch(command("PAUSE"), runtime("substrate://eap-demo/session-1"));

        assertThat(result.status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(result.dispatchMode()).isEqualTo("SUBSTRATE_RUNTIME");
        assertThat(result.resultMessage()).contains("eap-demo/session-1").contains("SUSPENDED");
        assertThat(result.acknowledgedAt()).isNotNull();
    }

    @Test
    void resumeAndRevertMapToControlApi() {
        when(client.resume(actor)).thenReturn(new SubstrateClient.ActorState("RUNNING"));
        when(client.revert(actor)).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));

        assertThat(adapter.dispatch(command("RESUME"), runtime("substrate://eap-demo/session-1")).status()).isEqualTo(ControlCommandStatus.ACKED);
        assertThat(adapter.dispatch(command("REVERT"), runtime("substrate://eap-demo/session-1")).status()).isEqualTo(ControlCommandStatus.ACKED);
        verify(client).resume(actor);
        verify(client).revert(actor);
    }

    @Test
    void noFreeWorkerIsReportedAsFailure() {
        when(client.resume(actor)).thenThrow(new SubstrateClient.SubstrateException("RESOURCE_EXHAUSTED", "no free workers available"));

        var result = adapter.dispatch(command("RESUME"), runtime("substrate://eap-demo/session-1"));

        assertThat(result.status()).isEqualTo(ControlCommandStatus.FAILED);
        assertThat(result.resultMessage()).contains("RESOURCE_EXHAUSTED").contains("没有空闲 worker");
        assertThat(result.acknowledgedAt()).isNull();
    }

    @Test
    void agentHandledCommandsAndInvalidAddressesAreNotSent() {
        var unsupported = adapter.dispatch(command("SET_RATE_LIMIT"), runtime("substrate://eap-demo/session-1"));
        var invalid = adapter.dispatch(command("PAUSE"), runtime("http://atenet-router/eap-demo/session-1"));

        assertThat(unsupported.status()).isEqualTo(ControlCommandStatus.FAILED);
        assertThat(unsupported.resultMessage()).contains("SET_RATE_LIMIT");
        assertThat(invalid.status()).isEqualTo(ControlCommandStatus.FAILED);
        verifyNoInteractions(client);
    }

    private static AgentControlCommand command(String type) {
        AgentControlCommand command = new AgentControlCommand();
        command.commandKey = "CTRL-test";
        command.agentId = 7L;
        command.commandType = type;
        return command;
    }

    private static AgentRuntimeInstance runtime(String endpoint) {
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getEndpoint()).thenReturn(endpoint);
        return runtime;
    }
}
