package com.eap.platform.control;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportedRuntimeControlTests {
    @Test void replacedActorCannotReceiveAnOldBindingsCommands(){
        var client=mock(SubstrateClient.class);var adapter=new SubstrateControlAdapter(client);
        var runtime=new AgentRuntimeInstance();
        ReflectionTestUtils.setField(runtime,"endpoint","substrate://eap-agents/outside");
        ReflectionTestUtils.setField(runtime,"importedActorUid","original");
        ReflectionTestUtils.setField(runtime,"importedActorTemplate","eap-agents/template");
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","outside","replacement","eap-agents/template","RUNNING"));
        var command=new AgentControlCommand();command.commandType="PAUSE";
        assertThat(adapter.dispatch(command,runtime).status()).isEqualTo(ControlCommandStatus.FAILED);
        verify(client,never()).suspend(any());
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","outside","original","eap-agents/template","RUNNING"));
        when(client.suspend(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));
        assertThat(adapter.dispatch(command,runtime).status()).isEqualTo(ControlCommandStatus.ACKED);
        command.commandType="REVERT";
        assertThat(adapter.dispatch(command,runtime).status()).isEqualTo(ControlCommandStatus.FAILED);
        verify(client,never()).revert(any());
    }
}
