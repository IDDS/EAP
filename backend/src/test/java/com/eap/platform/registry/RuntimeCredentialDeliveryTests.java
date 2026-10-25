package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.observability.ToolInvocationKind;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.budget.enabled=true"}) @ActiveProfiles("test")
class RuntimeCredentialDeliveryTests {
    @Autowired RuntimeCredentialDeliveryService delivery;
    @Autowired RuntimeConnectionService authentication;
    @Autowired RuntimeConnectionRepository connections;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired AgentService agents;
    @Autowired RuntimeConnectionProperties config;
    @Autowired SubstrateProperties props;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ScopedToolService scopes;
    @Autowired RuntimeToolPolicyService policies;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SubstrateClient client;
    AgentRuntimeInstance runtime;
    String request,key;
    volatile String installed;
    HttpServer router;
    KeyPair signing,encryption;
    String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    AtomicBoolean loseReply=new AtomicBoolean(),revokeOnInstall=new AtomicBoolean();
    AtomicReference<String> actorState=new AtomicReference<>("SUSPENDED");
    @BeforeEach void setup()throws Exception{
        signing=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);encryption=generator.generateKeyPair();
        config.setSigningKey("synthetic-independent-signing-material-only-for-tests");config.setDeliveryPrivateKey(Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()));config.setDeliveryPublicKey(Base64.getEncoder().encodeToString(signing.getPublic().getEncoded()));config.setLeaseSeconds(900);props.setEnabled(true);
        var agent=agents.create(new AgentDtos.AgentRequest("DELIVERY-"+UUID.randomUUID(),"Delivery test","","Test","Owner","Test",RiskLevel.LOW));agents.updateStatus(agent.id(),AgentLifecycleStatus.ACTIVE);
        runtime=new AgentRuntimeInstance();runtime.agentId=agent.id();runtime.instanceKey="eap-agents/delivery-"+agent.id();runtime.endpoint="substrate://"+runtime.instanceKey;runtime.protocol="SUBSTRATE_OBSERVED";runtime.importedActorUid=UUID.randomUUID().toString();runtime.importedActorTemplate="eap-agents/synthetic";runtimes.saveAndFlush(runtime);key=runtime.instanceKey;request=UUID.randomUUID().toString();
        when(client.inspect(any())).thenAnswer(call->new SubstrateClient.ActorInventory("eap-agents",key.substring(key.indexOf('/')+1),runtime.getBoundActorUid(),runtime.getBoundActorTemplate(),actorState.get()));
        when(client.resume(any())).thenAnswer(call->{actorState.set("RUNNING");return new SubstrateClient.ActorState("RUNNING");});
        when(client.suspend(any())).thenAnswer(call->{actorState.set("SUSPENDED");return new SubstrateClient.ActorState("SUSPENDED");});
        router=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        router.createContext("/eap/identity",e->{try{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isNull();assertThat(e.getRequestHeaders().getFirst("ate-target-actor")).isEqualTo(key);
            var wire=json.readTree(e.getRequestBody());byte[] body=Base64.getUrlDecoder().decode(wire.path("payload").asText());var verifier=Signature.getInstance("Ed25519");verifier.initVerify(signing.getPublic());verifier.update(body);assertThat(verifier.verify(Base64.getUrlDecoder().decode(wire.path("signature").asText()))).isTrue();var in=json.readTree(body);
            Map<String,?> response;
            if(e.getRequestURI().getPath().endsWith("challenge"))response=Map.of("protocol","EAP_IDENTITY_V1","operation",in.path("operation").asText(),"nonce",nonce,"publicKey",Base64.getUrlEncoder().withoutPadding().encodeToString(encryption.getPublic().getEncoded()),"actorUid",runtime.getBoundActorUid(),"instanceKey",key);
            else{
                var cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");cipher.init(Cipher.DECRYPT_MODE,encryption.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));installed=new String(cipher.doFinal(Base64.getUrlDecoder().decode(in.path("ciphertext").asText())),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(wire.toString()).doesNotContain(installed);
                if(revokeOnInstall.get())authentication.revoke(key,"test-revocation");
                if(loseReply.getAndSet(false)){e.sendResponseHeaders(503,-1);e.close();return;}
                response=Map.of("operation",in.path("operation").asText(),"proof",RuntimeConnectionService.hmac(installed,"eap-delivery-v1|"+in.path("operation").asText()+"|"+nonce+"|"+key+"|"+in.path("revision").asText()));
            }
            byte[] output=json.writeValueAsBytes(response);e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
        }catch(Exception ex){e.sendResponseHeaders(500,-1);e.close();}});
        router.start();props.setRouterUrl("http://127.0.0.1:"+router.getAddress().getPort());
    }
    @AfterEach void close(){System.out.println("SYNTHETIC_SCOPED_DECISIONS "+db.queryForList("select state,reason_code from scoped_tool_operations where agent_id=?",runtime.agentId));router.stop(0);}
    @Test void platformCreatedInstanceUsesSameEncryptedProtocolAndScopedToolAuthority() {
        runtime.platformActorUid=runtime.importedActorUid;runtime.platformActorTemplate=runtime.importedActorTemplate;
        runtime.importedActorUid=null;runtime.importedActorTemplate=null;runtime.protocol="SUBSTRATE";runtimes.saveAndFlush(runtime);
        delivery.connect(key,request,"test-factory");var access=authentication.authenticate(key,installed);
        assertThat(authentication.status(key).connection()).isEqualTo("READY");assertThat(authentication.status(key).automaticOnly()).isTrue();
        assertThatThrownBy(()->authentication.issue(key,UUID.randomUUID().toString(),"test")).isInstanceOf(BusinessException.class);assertThat(access.agentId()).isEqualTo(runtime.agentId);
        var policy=policies.get(key);policies.update(new RuntimeToolPolicyService.Update(key,policy.revision(),true,List.of(new RuntimeToolPolicyService.Tool(ScopedToolService.TOOL,ToolInvocationKind.TOOL)),4),"test");
        String resource="poc_"+UUID.randomUUID();var root=scopes.create(new ScopedToolDtos.Root(UUID.randomUUID().toString(),key,List.of(resource),List.of(key),1,1,Instant.now().plusSeconds(1800)),"test");
        var operation=new ScopedToolDtos.Execute(root.grantId(),UUID.randomUUID().toString(),resource,1);var effect=scopes.execute(access,operation);
        assertThat(effect.state()).as("scoped decision reason=%s",effect.reasonCode()).isEqualTo("COMMITTED");assertThat(scopes.execute(access,operation).effectId()).isEqualTo(effect.effectId());
        authentication.revoke(key,"test");assertThatThrownBy(()->delivery.connect(key,request,"test-factory")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->authentication.authenticate(key,installed)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void revocationBeforeFirstDeliveryCancelsOriginalCreationEvenAfterExplicitReapproval() {
        runtime.platformActorUid=runtime.importedActorUid;runtime.platformActorTemplate=runtime.importedActorTemplate;
        runtime.importedActorUid=null;runtime.importedActorTemplate=null;runtime.protocol="SUBSTRATE";runtimes.saveAndFlush(runtime);
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,attempted,delivery_request) values(?,?,?,?,?,true,?)",runtime.agentId,UUID.randomUUID().toString(),"dep-"+UUID.randomUUID(),key,runtime.platformActorUid,request);
        authentication.revoke(key,"test-owner");assertThat(authentication.status(key).connection()).isEqualTo("REVOKED");
        assertThatThrownBy(()->delivery.connectCreated(key,request,"test-factory")).isInstanceOf(ConflictException.class);verify(client,never()).resume(any());
        delivery.connect(key,UUID.randomUUID().toString(),"explicit-human-approval");assertThat(authentication.status(key).connection()).isEqualTo("READY");
        assertThatThrownBy(()->delivery.connectCreated(key,request,"test-factory")).isInstanceOf(ConflictException.class);
    }
    @Test void originalCreationReplayRenewsExpiredLeaseWithoutNewIdentityOrIdleWakeup() {
        runtime.platformActorUid=runtime.importedActorUid;runtime.platformActorTemplate=runtime.importedActorTemplate;
        runtime.importedActorUid=null;runtime.importedActorTemplate=null;runtime.protocol="SUBSTRATE";runtimes.saveAndFlush(runtime);
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,attempted,delivery_request) values(?,?,?,?,?,true,?)",runtime.agentId,UUID.randomUUID().toString(),"dep-"+UUID.randomUUID(),key,runtime.platformActorUid,request);
        delivery.connectCreated(key,request,"test-factory");var before=connections.findByInstanceKey(key).orElseThrow();String old=installed;
        db.update("update runtime_connections set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
        delivery.connectCreated(key,request,"test-factory");var after=connections.findByInstanceKey(key).orElseThrow();
        assertThat(after.generation).isEqualTo(before.generation);assertThat(after.identityExpiresAt).isEqualTo(before.identityExpiresAt);
        assertThat(after.credentialRevision).isNotEqualTo(before.credentialRevision);assertThat(authentication.status(key).connection()).isEqualTo("READY");
        assertThatThrownBy(()->authentication.authenticate(key,old)).isInstanceOf(UnauthorizedException.class);
        verify(client,times(2)).suspend(any());clearInvocations(client);delivery.connectCreated(key,request,"test-factory");verify(client,never()).resume(any());
        authentication.revoke(key,"owner");assertThatThrownBy(()->delivery.connectCreated(key,request,"test-factory")).isInstanceOf(ConflictException.class);verify(client,never()).resume(any());
    }
    @Test void incompletePlatformBindingFailsClosedWithoutLegacyFallback() {
        runtime.platformActorUid=runtime.importedActorUid;runtime.platformActorTemplate=null;
        runtime.importedActorUid=null;runtime.importedActorTemplate=null;runtime.protocol="SUBSTRATE";runtimes.saveAndFlush(runtime);
        assertThat(runtime.hasBoundIdentity()).isTrue();assertThat(runtime.supportsMachineIdentity()).isFalse();assertThat(authentication.executable(runtime)).isFalse();
        assertThatThrownBy(()->delivery.connect(key,request,"test")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->authentication.dispatchToken(runtime)).isInstanceOf(BusinessException.class);verify(client,never()).resume(any());
    }
    @Test void encryptedDeliveryIsVerifiedAndConsoleContainsMetadataOnly(){
        delivery.connect(key,request,"test-owner");var c=connections.findByInstanceKey(key).orElseThrow();assertThat(c.automaticDelivery).isTrue();assertThat(c.deliveryOperation).isNull();assertThat(authentication.authenticate(key,installed).generation()).isEqualTo(c.generation);assertThat(authentication.status(key).toString()).doesNotContain(installed);verify(client).suspend(any());
        String original=c.credentialRevision;delivery.connect(key,request,"test-owner");assertThat(connections.findByInstanceKey(key).orElseThrow().credentialRevision).isEqualTo(original);
    }
    @Test void lostReplyKeepsExactPendingOperationAndReplaysWithoutChangingIdentity(){
        loseReply.set(true);assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(BusinessException.class);var c=connections.findByInstanceKey(key).orElseThrow();String epoch=c.generation,revision=c.deliveryRevision,token=installed;
        assertThat(c.deliveryOperation).isEqualTo(request);assertThat(authentication.status(key).connection()).isEqualTo("DELIVERY_PENDING");assertThat(authentication.status(key).deliveryOperation()).isEqualTo(request);assertThatThrownBy(()->authentication.authenticate(key,token)).isInstanceOf(UnauthorizedException.class);
        delivery.connect(key,request,"test-owner");c=connections.findByInstanceKey(key).orElseThrow();assertThat(c.generation).isEqualTo(epoch);assertThat(c.credentialRevision).isEqualTo(revision);assertThat(installed).isEqualTo(token);
    }
    @Test void revocationBetweenDeliveryAndReceiptWinsAndOriginalRequestCannotReenable(){
        revokeOnInstall.set(true);assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(ConflictException.class);var c=connections.findByInstanceKey(key).orElseThrow();assertThat(c.generation).isNull();assertThat(c.automaticDelivery).isFalse();assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(ConflictException.class);assertThatThrownBy(()->authentication.authenticate(key,installed)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void expiryRenewalPreservesToolIntentEffectAndSharedBudgetButRejectsOldLease(){
        delivery.connect(key,request,"test-owner");String old=installed;var before=connections.findByInstanceKey(key).orElseThrow();var access=authentication.authenticate(key,old);var policy=policies.get(key);policies.update(new RuntimeToolPolicyService.Update(key,policy.revision(),true,List.of(new RuntimeToolPolicyService.Tool(ScopedToolService.TOOL,ToolInvocationKind.TOOL)),4),"test");
        String resource="poc_"+UUID.randomUUID();var root=scopes.create(new ScopedToolDtos.Root(UUID.randomUUID().toString(),key,List.of(resource),List.of(key),3,2,Instant.now().plusSeconds(1800)),"test");var operation=new ScopedToolDtos.Execute(root.grantId(),UUID.randomUUID().toString(),resource,1);var effect=scopes.execute(access,operation);assertThat(effect.state()).as("scoped decision reason=%s",effect.reasonCode()).isEqualTo("COMMITTED");
        db.update("update runtime_connections set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
        assertThatThrownBy(()->authentication.authenticate(key,old)).isInstanceOf(UnauthorizedException.class);delivery.ensure(key);var after=connections.findByInstanceKey(key).orElseThrow();assertThat(after.generation).isEqualTo(before.generation);assertThat(after.credentialRevision).isNotEqualTo(before.credentialRevision);assertThat(installed).isNotEqualTo(old);assertThatThrownBy(()->authentication.authenticate(key,old)).isInstanceOf(UnauthorizedException.class);
        var fresh=authentication.authenticate(key,installed);assertThat(scopes.execute(fresh,operation).effectId()).isEqualTo(effect.effectId());assertThat(policies.get(key).claimedCount()).isEqualTo(1);assertThat(db.queryForObject("select used_amount from budget_accounts where scope_key=? and unit='TOOL_CLAIMS'",Long.class,"tool-root:"+root.grantId())).isEqualTo(1);
    }
    @Test void concurrentRenewalSharesOnePendingLeaseAndDoesNotChangeIdentity()throws Exception{
        delivery.connect(key,request,"test-owner");var before=connections.findByInstanceKey(key).orElseThrow();
        db.update("update runtime_connections set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<java.util.concurrent.Callable<Void>>();for(int i=0;i<8;i++)tasks.add(()->{delivery.ensure(key);return null;});
            for(var future:pool.invokeAll(tasks))future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        var after=connections.findByInstanceKey(key).orElseThrow();assertThat(after.generation).isEqualTo(before.generation);assertThat(after.deliveryOperation).isNull();assertThat(after.credentialRevision).isNotEqualTo(before.credentialRevision);
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='DELIVER_RUNTIME_CREDENTIAL'",Long.class,runtime.agentId.toString())).isEqualTo(2);
    }
    @ParameterizedTest
    @CsvSource({"true,-1", "false,-1", "true,60", "false,60"})
    void dispatchUsesInstalledLeaseWhenCallerAlreadyReadConnection(boolean readOnly, int remainingSeconds) {
        delivery.connect(key,request,"test-owner");
        var before=connections.findByInstanceKey(key).orElseThrow();String old=installed;
        db.update("update runtime_connections set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().plusSeconds(remainingSeconds)),key);
        var caller=new TransactionTemplate(transactions);caller.setReadOnly(readOnly);
        caller.executeWithoutResult(status->{
            // Experiment profile and submission both read admission in their own transaction.
            assertThat(authentication.executable(runtime)).isTrue();
            String dispatched=authentication.dispatchToken(runtime);
            assertThat(MessageDigest.isEqual(dispatched.getBytes(java.nio.charset.StandardCharsets.UTF_8),installed.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .as("dispatch must use the lease installed by independent renewal").isTrue();
        });
        var after=connections.findByInstanceKey(key).orElseThrow();
        assertThat(after.generation).isEqualTo(before.generation);
        assertThat(after.identityExpiresAt).isEqualTo(before.identityExpiresAt);
        assertThat(after.credentialSerial).isEqualTo(before.credentialSerial+1);
        assertThat(after.deliveryOperation).isNull();assertThat(after.expiresAt).isAfter(Instant.now());
        assertThat(authentication.authenticate(key,installed).agentId()).isEqualTo(runtime.agentId);
        assertThatThrownBy(()->authentication.authenticate(key,old)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void restoredOldGuestReceivesCurrentLeaseWithoutChangingAuthorityOrBudget(){
        delivery.connect(key,request,"test-owner");var before=connections.findByInstanceKey(key).orElseThrow();String current=installed;installed=null;
        delivery.ensure(key);var after=connections.findByInstanceKey(key).orElseThrow();assertThat(installed).isEqualTo(current);assertThat(after.credentialRevision).isEqualTo(before.credentialRevision);assertThat(after.credentialSerial).isEqualTo(before.credentialSerial);assertThat(after.generation).isEqualTo(before.generation);
    }
    @Test void leaseCannotExtendIdentityAuthorityAndToolsCannotExceedIt(){
        delivery.connect(key,request,"test-owner");var p=policies.get(key);policies.update(new RuntimeToolPolicyService.Update(key,p.revision(),true,List.of(new RuntimeToolPolicyService.Tool(ScopedToolService.TOOL,ToolInvocationKind.TOOL)),4),"test");
        db.update("update runtime_connections set identity_expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().plusSeconds(300)),key);
        assertThatThrownBy(()->scopes.create(new ScopedToolDtos.Root(UUID.randomUUID().toString(),key,List.of("poc_"+UUID.randomUUID()),List.of(key),1,1,Instant.now().plusSeconds(600)),"test")).isInstanceOf(BusinessException.class).hasMessageContaining("持有实例接入授权");
        db.update("update runtime_connections set identity_expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
        assertThat(authentication.executable(runtime)).isFalse();assertThatThrownBy(()->delivery.ensure(key)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->authentication.authenticate(key,installed)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void recreatedUidPreventsDeliveryAndAnyTaskResume(){
        when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents",key.substring(key.indexOf('/')+1),UUID.randomUUID().toString(),runtime.importedActorTemplate,"RUNNING"));assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(ConflictException.class);verify(client,never()).resume(any());assertThat(connections.findByInstanceKey(key)).isEmpty();
    }

    @Test void transientSuspendConflictRestoresSameInstanceWithoutRedelivery() {
        when(client.suspend(any())).thenThrow(new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor"))
                .thenAnswer(call->{actorState.set("SUSPENDED");return new SubstrateClient.ActorState("SUSPENDED");});
        assertThatCode(()->delivery.connect(key,request,"test-owner")).doesNotThrowAnyException();
        var confirmed=connections.findByInstanceKey(key).orElseThrow();
        assertThat(actorState.get()).isEqualTo("SUSPENDED");
        assertThat(confirmed.deliveryOperation).isNull();
        assertThat(authentication.authenticate(key,installed).generation()).isEqualTo(confirmed.generation);
        verify(client,times(1)).resume(any());verify(client,times(2)).suspend(any());
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='DELIVER_RUNTIME_CREDENTIAL'",Long.class,runtime.agentId.toString())).isEqualTo(1);
        delivery.connect(key,request,"test-owner");
        assertThat(connections.findByInstanceKey(key).orElseThrow().credentialRevision).isEqualTo(confirmed.credentialRevision);
        verify(client,times(1)).resume(any());
    }

    @Test void repeatedSuspendConflictStopsWithoutReplacingTheConfirmedIdentity() {
        when(client.suspend(any())).thenThrow(new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor"));
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("休眠");
        var confirmed=connections.findByInstanceKey(key).orElseThrow();
        assertThat(confirmed.deliveryOperation).isNull();assertThat(actorState.get()).isEqualTo("RUNNING");
        assertThat(authentication.authenticate(key,installed).generation()).isEqualTo(confirmed.generation);
        verify(client,times(1)).resume(any());verify(client,times(4)).suspend(any());
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='DELIVER_RUNTIME_CREDENTIAL'",Long.class,runtime.agentId.toString())).isEqualTo(1);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"UNAVAILABLE","PERMISSION_DENIED","DEADLINE_EXCEEDED","ABORTED"})
    void unrecognizedSuspendFailuresAreNeverRetried(String code) {
        when(client.suspend(any())).thenThrow(new SubstrateClient.SubstrateException(code,"synthetic non-busy failure"));
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner"))
                .isInstanceOf(SubstrateClient.SubstrateException.class);
        verify(client,times(1)).suspend(any());verify(client,times(1)).resume(any());
    }

    @Test void replacementAfterSuspendConflictPreventsAnotherSuspend() {
        when(client.suspend(any())).thenAnswer(call->{
            when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents",key.substring(key.indexOf('/')+1),UUID.randomUUID().toString(),runtime.getBoundActorTemplate(),"RUNNING"));
            throw new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor");
        });
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(ConflictException.class);
        verify(client,times(1)).suspend(any());
    }

    @Test void aConcurrentConfirmedSuspendDoesNotNeedAnotherMutation() {
        when(client.suspend(any())).thenAnswer(call->{
            actorState.set("SUSPENDED");
            throw new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor");
        });
        assertThatCode(()->delivery.connect(key,request,"test-owner")).doesNotThrowAnyException();
        verify(client,times(1)).suspend(any());assertThat(actorState.get()).isEqualTo("SUSPENDED");
    }

    @Test void suspensionReplyMustMatchTheOriginalActorAfterTheRpc() {
        when(client.suspend(any())).thenAnswer(call->{
            when(client.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents",key.substring(key.indexOf('/')+1),UUID.randomUUID().toString(),runtime.getBoundActorTemplate(),"SUSPENDED"));
            return new SubstrateClient.ActorState("SUSPENDED");
        });
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner")).isInstanceOf(ConflictException.class);
        verify(client,times(1)).suspend(any());
    }

    @Test void aNonSuspendedReplyRemainsUnconfirmedWithoutRetryingUnknownEffects() {
        when(client.suspend(any())).thenReturn(new SubstrateClient.ActorState("RUNNING"));
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("休眠");
        verify(client,times(1)).suspend(any());
    }

    @Test void interruptedRestoreStopsAndPreservesThreadInterruption() {
        when(client.suspend(any())).thenAnswer(call->{Thread.currentThread().interrupt();throw new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor");});
        try {
            assertThatThrownBy(()->delivery.connect(key,request,"test-owner"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();verify(client,times(1)).suspend(any());
        } finally { Thread.interrupted(); }
    }

    @Test void failedDeliveryRemainsThePrimaryErrorWhenRestoreAlsoFails() {
        loseReply.set(true);
        when(client.suspend(any())).thenThrow(new SubstrateClient.SubstrateException("ABORTED","another operation is in progress for this actor"));
        assertThatThrownBy(()->delivery.connect(key,request,"test-owner"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("安全配送接口未确认成功")
                .satisfies(e->assertThat(e.getSuppressed()).hasSize(1));
        var pending=connections.findByInstanceKey(key).orElseThrow();
        assertThat(pending.deliveryOperation).isEqualTo(request);assertThat(pending.verifiedAt).isNull();
        verify(client,times(4)).suspend(any());
    }

    @Test void anInitiallyRunningInstanceIsNotSuspendedByDelivery() {
        actorState.set("RUNNING");delivery.connect(key,request,"test-owner");
        verify(client,never()).suspend(any());assertThat(actorState.get()).isEqualTo("RUNNING");
    }
}
