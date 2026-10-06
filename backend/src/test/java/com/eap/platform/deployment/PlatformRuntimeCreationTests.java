package com.eap.platform.deployment;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.registry.*;
import com.eap.platform.substrate.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.flyway.enabled=true") @ActiveProfiles("test")
class PlatformRuntimeCreationTests {
    @Autowired PlatformRuntimeCreationService service;
    @Autowired AgentService agents;
    @Autowired AgentRegistryService registry;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired JdbcTemplate db;
    @MockitoBean SubstrateClient nativeClient;
    @MockitoBean RuntimeCredentialDeliveryService delivery;
    @MockitoBean RuntimeConnectionService connections;
    SubstrateDeployment deployment;
    String request,uid,key;
    @BeforeEach void setup() {
        var agent=agents.create(new AgentDtos.AgentRequest("CREATE-"+UUID.randomUUID(),"Platform creation test","","Test","Owner","Test",RiskLevel.LOW));
        agents.updateStatus(agent.id(),AgentLifecycleStatus.ACTIVE);
        deployment=new SubstrateDeployment();deployment.agentId=agent.id();deployment.version=1;deployment.deploymentKey="dep-"+UUID.randomUUID();deployment.atespace="eap-agents";deployment.templateName="eap-a"+agent.id()+"-v1";
        request=UUID.randomUUID().toString();uid=UUID.randomUUID().toString();key="eap-agents/a"+agent.id()+"-v1-"+request.replace("-","").substring(0,12);
        when(nativeClient.createActorWithIdentity(any(),any())).thenAnswer(c->actor(uid));when(nativeClient.inspect(any())).thenAnswer(c->actor(uid));
        when(connections.status(key)).thenReturn(new RuntimeConnectionService.Status(key,agent.id(),true,"MATCHED","READY",null,null,null,null,"","",true,true,"",null,null,null,true,false));
    }
    SubstrateClient.ActorInventory actor(String identity) { return new SubstrateClient.ActorInventory("eap-agents",key.substring(key.indexOf('/')+1),identity,"eap-agents/"+deployment.templateName,"SUSPENDED"); }
    @Test void sameRequestKeepsActorUidAndOriginalDeliveryRequest() {
        assertThat(service.create(deployment,request,List.of("backend.eap.svc"))).isEqualTo(key);service.connect(deployment,request,"owner");
        String original=db.queryForObject("select delivery_request from platform_runtime_creations where instance_key=?",String.class,key);
        assertThat(service.create(deployment,request,List.of("backend.eap.svc"))).isEqualTo(key);service.connect(deployment,request,"owner");
        verify(nativeClient,times(1)).createActorWithIdentity(any(),any());verify(delivery,times(2)).connectCreated(key,original,"owner");
        var runtime=runtimes.findByInstanceKey(key).orElseThrow();assertThat(runtime.hasBoundIdentity()).isTrue();assertThat(runtime.getImportedActorUid()).isNull();assertThat(runtime.getBoundActorUid()).isEqualTo(uid);
    }
    @Test void revokedCreationIntentCannotAlterNativePolicyOrDeliverAfterReapproval() {
        service.create(deployment,request,List.of());db.update("update platform_runtime_creations set revoked=true where instance_key=?",key);
        clearInvocations(nativeClient);
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.connect(deployment,request,"owner")).isInstanceOf(ConflictException.class);
        verifyNoInteractions(nativeClient,delivery);
    }
    @Test void lostNativeReplyNeverSendsCreateAgainOrAdoptsByName() {
        when(nativeClient.createActorWithIdentity(any(),any())).thenThrow(new SubstrateClient.SubstrateException("DEADLINE_EXCEEDED","synthetic lost reply"));
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(SubstrateClient.SubstrateException.class);
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(ConflictException.class);
        verify(nativeClient,times(1)).createActorWithIdentity(any(),any());assertThat(runtimes.findByInstanceKey(key)).isEmpty();verifyNoInteractions(delivery);
    }
    @Test void policyFailureRetriesSameUidWithoutDeletingTheActor() {
        doThrow(new SubstrateClient.SubstrateException("UNAVAILABLE","synthetic policy failure")).doNothing().when(nativeClient).setEgressHostnames(any(),any());
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(SubstrateClient.SubstrateException.class);
        assertThat(service.create(deployment,request,List.of())).isEqualTo(key);verify(nativeClient,times(1)).createActorWithIdentity(any(),any());verify(nativeClient,never()).deleteActor(any());
    }
    @Test void recreatedUidBlocksCreationReplayAndCredentialDelivery() {
        service.create(deployment,request,List.of());when(nativeClient.inspect(any())).thenReturn(actor(UUID.randomUUID().toString()));
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.connect(deployment,request,"owner")).isInstanceOf(ConflictException.class);verifyNoInteractions(delivery);
    }
    @Test void ordinaryRegistrationAndHeartbeatCannotDowngradeBoundIdentity() {
        service.create(deployment,request,List.of());
        assertThatThrownBy(()->registry.registerRuntime(deployment.agentId,new AgentRegistryDtos.RuntimeInstanceRequest(key,"http://untrusted","A2A",true))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->registry.heartbeat(key,new AgentRegistryDtos.HeartbeatRequest(RuntimeStatus.ONLINE,0))).isInstanceOf(BusinessException.class);
    }
    @Test void deletedIdentityCannotBeRecreatedUsingOriginalRequest() {
        service.create(deployment,request,List.of());var runtime=runtimes.findByInstanceKey(key).orElseThrow();runtime.markOffline();runtimes.saveAndFlush(runtime);
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(ConflictException.class);verify(nativeClient,times(1)).createActorWithIdentity(any(),any());
    }
    @Test void requestCannotMoveToAnotherVersionAfterSuccessfulCreation() {
        service.create(deployment,request,List.of());String first=deployment.deploymentKey;deployment.deploymentKey="dep-"+UUID.randomUUID();
        assertThat(service.existingDeployment(deployment.agentId,request)).isEqualTo(first);
        assertThatThrownBy(()->service.create(deployment,request,List.of())).isInstanceOf(ConflictException.class);verify(nativeClient,times(1)).createActorWithIdentity(any(),any());
    }
}
