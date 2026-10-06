package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeInventoryServiceTests {
    final SubstrateClient client=mock(SubstrateClient.class);
    final SubstrateProperties props=new SubstrateProperties();
    final AgentRuntimeInstanceRepository runtimes=mock(AgentRuntimeInstanceRepository.class);
    final AgentService agents=mock(AgentService.class);
    final AuditLogRepository audit=mock(AuditLogRepository.class);
    final SubstrateClient.ActorInventory actor=new SubstrateClient.ActorInventory("eap-agents","outside","uid-one","eap-agents/template","SUSPENDED");
    RuntimeInventoryService service;
    RuntimeInventoryService.Adopt request=new RuntimeInventoryService.Adopt("eap-agents","outside","uid-one","eap-agents/template",71L);
    @BeforeEach void setup(){
        props.setEnabled(true);
        var agent=new AgentDtos.AgentResponse(71L,"IMPORTED","业务助手","","业务部","负责人","助手",AgentLifecycleStatus.DRAFT,RiskLevel.LOW,AgentRecordOrigin.PRODUCTION,null,null);
        when(agents.get(71L)).thenReturn(agent);when(agents.list()).thenReturn(List.of(agent));
        when(client.inspect(new SubstrateActorRef("eap-agents","outside"))).thenReturn(actor);
        when(client.listActors("eap-agents","")).thenReturn(new SubstrateClient.ActorPage(List.of(actor),""));
        when(runtimes.findAll()).thenReturn(List.of());
        service=new RuntimeInventoryService(client,props,runtimes,agents,audit,mock(RuntimeConnectionService.class));
    }
    @Test void discoveryOnlyReadsMetadataAndDoesNotCreateOrControl(){
        var snapshot=service.snapshot();
        assertThat(snapshot.scopes().getFirst().items().getFirst().enrollment()).isEqualTo("UNMANAGED");
        verify(runtimes,never()).saveAndFlush(any());verifyNoInteractions(audit);
        verify(client,never()).resume(any());verify(client,never()).setEgressHostnames(any(),any());
    }
    @Test void enrollmentBindsObservedIdentityAndDoesNotImplyExecutionOrContentReadiness(){
        var result=service.adopt(request,"verified-subject");
        assertThat(result.enrollment()).isEqualTo("IMPORTED");assertThat(result.governance()).isEqualTo("UNVERIFIED");
        var capture=org.mockito.ArgumentCaptor.forClass(AgentRuntimeInstance.class);
        verify(runtimes).saveAndFlush(capture.capture());
        assertThat(capture.getValue().protocol).isEqualTo("SUBSTRATE_OBSERVED");
        assertThat(capture.getValue().importedActorUid).isEqualTo("uid-one");
        assertThat(capture.getValue().status).isEqualTo(RuntimeStatus.DEGRADED);
        verify(client,never()).setEgressHostnames(any(),any());verify(client,never()).resume(any());
        verify(audit).save(argThat(event -> "verified-subject".equals(org.springframework.test.util.ReflectionTestUtils.getField(event,"actor"))));
    }
    @Test void foreignScopesAndChangedUidOrTemplateAreRejected(){
        assertThatThrownBy(()->service.adopt(new RuntimeInventoryService.Adopt("other","outside","uid-one","eap-agents/template",71L),"x")).isInstanceOf(BusinessException.class);
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","outside","replacement","eap-agents/template","RUNNING"));
        assertThatThrownBy(()->service.adopt(request,"x")).isInstanceOf(ConflictException.class);
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","outside","uid-one","eap-agents/changed","RUNNING"));
        assertThatThrownBy(()->service.adopt(request,"x")).isInstanceOf(ConflictException.class);
        verify(runtimes,never()).saveAndFlush(any());
    }
    @Test void repeatedEnrollmentIsIdempotentButCannotStealAnInstance(){
        var bound=new AgentRuntimeInstance();bound.agentId=71L;bound.importedActorUid="uid-one";bound.importedActorTemplate=actor.template();
        when(runtimes.findByInstanceKey("eap-agents/outside")).thenReturn(Optional.of(bound));
        assertThat(service.adopt(request,"x").enrollment()).isEqualTo("IMPORTED");verifyNoInteractions(audit);
        bound.agentId=72L;
        assertThatThrownBy(()->service.adopt(request,"x")).isInstanceOf(ConflictException.class);
    }
    @Test void partialOrUnavailableScopeNeverLooksLikeAnEmptyHealthyInventory(){
        when(client.listActors("eap-agents","")).thenReturn(new SubstrateClient.ActorPage(List.of(actor),"next"));
        when(client.listActors("eap-agents","next")).thenThrow(new SubstrateClient.SubstrateException("UNAVAILABLE","sensitive provider detail"));
        var scope=service.snapshot().scopes().getFirst();
        assertThat(scope.status()).isEqualTo("UNAVAILABLE");assertThat(scope.items()).isEmpty();
        assertThat(scope.toString()).doesNotContain("sensitive");
    }
    @Test void missingAndRecreatedBoundInstancesAreExplicit(){
        var bound=new AgentRuntimeInstance();bound.agentId=71L;bound.instanceKey="eap-agents/outside";bound.importedActorUid="older-uid";bound.importedActorTemplate=actor.template();
        when(runtimes.findAll()).thenReturn(List.of(bound));
        assertThat(service.snapshot().scopes().getFirst().items().getFirst().enrollment()).isEqualTo("IDENTITY_CHANGED");
        when(client.listActors("eap-agents","")).thenReturn(new SubstrateClient.ActorPage(List.of(),""));
        assertThat(service.snapshot().scopes().getFirst().items().getFirst().state()).isEqualTo("MISSING");
    }
    @Test void registryCannotOverwriteEnrollmentOrMoveExistingOwnership(){
        var repo=mock(AgentRepository.class);when(repo.existsById(anyLong())).thenReturn(true);
        var service2=new AgentRegistryService(repo,mock(AgentCapabilityRepository.class),runtimes,new RuntimeLeaseProperties());
        var bound=new AgentRuntimeInstance();bound.agentId=71L;bound.importedActorUid="uid-one";
        when(runtimes.findByInstanceKey("eap-agents/outside")).thenReturn(Optional.of(bound));
        assertThatThrownBy(()->service2.registerRuntime(71L,new AgentRegistryDtos.RuntimeInstanceRequest("eap-agents/outside","http://changed","HTTP",false))).isInstanceOf(ConflictException.class);
        bound.importedActorUid=null;bound.agentId=72L;
        assertThatThrownBy(()->service2.registerRuntime(71L,new AgentRegistryDtos.RuntimeInstanceRequest("eap-agents/outside","http://changed","HTTP",false))).isInstanceOf(ConflictException.class);
    }
    @Test void enrollmentTimestampIsNotFabricatedAsHeartbeatAndDoesNotPermitTaskDispatch(){
        var bound=new AgentRuntimeInstance();bound.importedActorUid="uid-one";bound.protocol="SUBSTRATE_OBSERVED";
        bound.prePersist();
        assertThat(bound.lastHeartbeatAt).isNull();
        var execution=new com.eap.platform.workflow.WorkflowExecutionProperties();
        execution.setEnabled(true);execution.setAllowedHosts("localhost");execution.setToken("synthetic-test-token");
        var tasks=new com.eap.platform.workflow.WorkflowTaskClient(execution,new com.fasterxml.jackson.databind.ObjectMapper(),props,client,mock(com.eap.platform.registry.RuntimeConnectionService.class),mock(ScopedToolService.class));
        try {
            assertThatThrownBy(()->tasks.invoke(bound,Map.of(),java.time.Instant.now().plusSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("尚未验证任务协议");
            verifyNoInteractions(client);
        } finally { tasks.close(); }
    }
}
