package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeConnectionTests {
    final RuntimeConnectionRepository repo=mock(RuntimeConnectionRepository.class);
    final AgentRuntimeInstanceRepository runtimes=mock(AgentRuntimeInstanceRepository.class);
    final RuntimeConnectionProperties config=new RuntimeConnectionProperties();
    final SubstrateProperties props=new SubstrateProperties();
    final SubstrateClient client=mock(SubstrateClient.class);
    final AgentService agents=mock(AgentService.class);
    final AuditLogRepository audit=mock(AuditLogRepository.class);
    final TraceSpanObservationRepository spans=mock(TraceSpanObservationRepository.class);
    final ObjectMapper json=new ObjectMapper();
    final AgentRuntimeInstance runtime=new AgentRuntimeInstance();
    final String key="eap-agents/external";
    RuntimeConnection saved;
    RuntimeConnectionService service;
    @BeforeEach void setup(){
        runtime.id=901L;runtime.agentId=71L;runtime.instanceKey=key;runtime.endpoint="substrate://"+key;
        runtime.protocol=SubstrateActorRef.OBSERVED_PROTOCOL;runtime.importedActorUid="uid-1";runtime.importedActorTemplate="eap-agents/template";
        config.setSigningKey("synthetic-independent-signing-material-only-for-tests");props.setEnabled(true);
        when(runtimes.findByInstanceKey(key)).thenReturn(Optional.of(runtime));
        when(runtimes.lockForScopedTools(key)).thenReturn(Optional.of(runtime));
        when(repo.findByInstanceKey(key)).thenAnswer(i->Optional.ofNullable(saved));
        when(repo.lockForToolAccess(key)).thenAnswer(i->Optional.ofNullable(saved));
        when(repo.saveAndFlush(any())).thenAnswer(i->{saved=i.getArgument(0);return saved;});
        when(agents.get(71L)).thenReturn(new AgentDtos.AgentResponse(71L,"TEST","External","","Test","Owner","Test",AgentLifecycleStatus.ACTIVE,RiskLevel.LOW,AgentRecordOrigin.PRODUCTION,null,null));
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","external","uid-1",runtime.importedActorTemplate,"SUSPENDED"));
        when(client.resume(any())).thenReturn(new SubstrateClient.ActorState("RUNNING"));
        when(client.suspend(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));
        service=new RuntimeConnectionService(repo,runtimes,config,props,client,agents,audit,json,spans);
    }
    @AfterEach void close(){service.close();}
    RuntimeConnectionService.Credential issue(){return service.issue(key,UUID.randomUUID().toString(),"operator-subject");}
    @Test void inspectionCredentialCannotTriggerAutomaticDeliveryOrRenewal(){
        var credential=issue();saved.verifiedAt=Instant.now();
        var delivery=mock(RuntimeCredentialDeliveryService.class);service.delivery=delivery;
        assertThat(service.inspectionToken(runtime)).isEqualTo(credential.token());verifyNoInteractions(delivery);
        saved.expiresAt=Instant.now().minusSeconds(1);
        assertThatThrownBy(()->service.inspectionToken(runtime)).isInstanceOf(BusinessException.class);verifyNoInteractions(delivery);
        verify(client,never()).resume(any());
    }
    @Test void inspectionRequiresVerifiedInstalledIdentityAndNoPendingDelivery(){
        issue();assertThatThrownBy(()->service.inspectionToken(runtime)).isInstanceOf(BusinessException.class);
        saved.verifiedAt=Instant.now();saved.deliveryOperation="pending-fixture";
        assertThatThrownBy(()->service.inspectionToken(runtime)).isInstanceOf(BusinessException.class);
        saved.deliveryOperation=null;
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","external","replacement",runtime.importedActorTemplate,"RUNNING"));
        assertThatThrownBy(()->service.inspectionToken(runtime)).isInstanceOf(ConflictException.class);
    }
    @Test void credentialIsScopedNotPersistedAndReplayDoesNotRotate(){
        String request=UUID.randomUUID().toString();var first=service.issue(key,request,"operator-subject");
        assertThat(service.issue(key,request,"operator-subject").token()).isEqualTo(first.token());
        assertThat(saved.generation).isNotEqualTo(request).doesNotContain(first.token());
        assertThat(first.toString()).doesNotContain(first.token());
        assertThat(service.executable(runtime)).isFalse();
        assertThatThrownBy(()->service.authenticate(key,first.token())).isInstanceOf(UnauthorizedException.class);
        saved.verifiedAt=Instant.now();assertThat(service.authenticate(key,first.token()).agentId()).isEqualTo(71L);
        runtime.agentId=72L;
        assertThatThrownBy(()->service.authenticate(key,first.token())).isInstanceOf(UnauthorizedException.class);
    }
    @Test void revocationExpiryAndRotationDenyOldCredentialsAndDispatch(){
        var first=issue();saved.verifiedAt=Instant.now();service.revoke(key,"operator-subject");
        assertThat(service.executable(runtime)).isFalse();
        assertThatThrownBy(()->service.authenticate(key,first.token())).isInstanceOf(UnauthorizedException.class);
        var second=issue();saved.verifiedAt=Instant.now();assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(()->service.authenticate(key,first.token())).isInstanceOf(UnauthorizedException.class);
        saved.expiresAt=Instant.now().minusSeconds(1);
        assertThatThrownBy(()->service.dispatchToken(runtime)).isInstanceOf(BusinessException.class);
    }
    @Test void recreatedActorCannotUseOldCredentialOrReceiveDispatch(){
        var credential=issue();saved.verifiedAt=Instant.now();
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","external","replacement",runtime.importedActorTemplate,"RUNNING"));
        assertThat(service.status(key).connection()).isEqualTo("BLOCKED");
        assertThatThrownBy(()->service.dispatchToken(runtime)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.authenticate(key,credential.token())).isInstanceOf(UnauthorizedException.class);
        verify(client,never()).resume(any());
    }
    @Test void signedProbeEnablesTaskAndRestoresSleepWithoutSendingCredential()throws Exception{
        var credential=issue();var router=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        router.createContext("/eap/integration",exchange->{
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("ate-target-actor")).isEqualTo(key);
            var request=json.readTree(exchange.getRequestBody());assertThat(request.toString()).doesNotContain(credential.token());
            String nonce=request.path("nonce").asText();
            byte[] response=json.writeValueAsBytes(Map.of("nonce",nonce,"agentId",71,"instanceKey",key,"protocol","EAP_HTTP_TASK_V1","proof",RuntimeConnectionService.hmac(credential.token(),nonce+"|71|"+key+"|EAP_HTTP_TASK_V1")));
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });router.start();props.setRouterUrl("http://127.0.0.1:"+router.getAddress().getPort());
        try{assertThat(service.verify(key,"operator-subject").connection()).isEqualTo("READY");assertThat(service.dispatchToken(runtime)).isEqualTo(credential.token());verify(client).suspend(any());}
        finally{router.stop(0);}
    }
    @Test void invalidProbeClearsEarlierReadinessAndStillRestoresSleep()throws Exception{
        issue();saved.verifiedAt=Instant.now();var router=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        router.createContext("/eap/integration",e->{e.sendResponseHeaders(200,2);e.getResponseBody().write("{}".getBytes());e.close();});router.start();props.setRouterUrl("http://127.0.0.1:"+router.getAddress().getPort());
        try{var status=service.verify(key,"operator-subject");assertThat(status.connection()).isEqualTo("PENDING");assertThat(status.message()).contains("未开放执行");assertThat(service.executable(runtime)).isFalse();verify(client).suspend(any());}finally{router.stop(0);}
    }
    @Test void evidenceRequiresAcceptedGatewayAndMatchingStoredScopedTrace(){
        var credential=issue();saved.verifiedAt=Instant.now();var access=service.authenticate(key,credential.token());
        service.recordTrace(access,Set.of("trace"));assertThat(saved.traceAt).isNull();
        service.recordGateway(access,"trace");service.recordTrace(access,Set.of("other"));assertThat(saved.traceAt).isNull();
        service.recordTrace(access,Set.of("trace"));assertThat(saved.traceAt).isNull();
        when(spans.existsByTraceIdAndAgentIdAndEndpointKey("trace",71L,"runtime-901")).thenReturn(true);
        service.recordTrace(access,Set.of("trace"));assertThat(service.evidence(key)).isEqualTo("SCOPED_PATH");
        issue();service.recordGateway(access,"stale");assertThat(saved.gatewayAt).isNull();
    }
    @Test void scopedOtlpRejectsForeignIdentityAndNamespacesTraceAndSpanIds()throws Exception{
        var access=new RuntimeConnectionService.Access(key,71L,"generation","runtime-901");
        var source=json.readTree("{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"eap.agent.id\",\"value\":{\"stringValue\":\"71\"}}]},\"scopeSpans\":[{\"spans\":[{\"traceId\":\"11111111111111111111111111111111\",\"spanId\":\"2222222222222222\",\"name\":\"agent.task\"}]}]}]}");
        var result=RuntimeScopedTelemetry.normalize(source,access);
        assertThat(result.traces()).containsExactly(RuntimeScopedTelemetry.trace(key,"11111111111111111111111111111111"));
        assertThat(result.payload().at("/resourceSpans/0/scopeSpans/0/spans/0/spanId").asText()).isNotEqualTo("2222222222222222");
        assertThat(RuntimeScopedTelemetry.trace("eap-agents/other","11111111111111111111111111111111")).isNotEqualTo(result.traces().iterator().next());
        var foreign=json.readTree(source.toString().replace("\"71\"","\"72\""));
        assertThatThrownBy(()->RuntimeScopedTelemetry.normalize(foreign,access)).isInstanceOf(BusinessException.class);
    }
}
