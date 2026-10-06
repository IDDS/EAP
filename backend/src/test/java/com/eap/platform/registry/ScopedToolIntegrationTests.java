package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.observability.ToolInvocationKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.budget.enabled=true"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class ScopedToolIntegrationTests {
    @Autowired ScopedToolService service;
    @Autowired RuntimeToolPolicyService policies;
    @Autowired RuntimeConnectionRepository connections;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired AgentService agents;
    @Autowired BudgetLedger budget;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean RuntimeConnectionService authentication;
    @MockitoSpyBean RuntimeToolGuardService guard;
    RuntimeConnectionService.Access a,b;
    String resource,other;
    Instant expires;
    @BeforeEach void setup(){a=identity();b=identity();resource="poc_"+UUID.randomUUID();other="poc_"+UUID.randomUUID();expires=Instant.now().plusSeconds(900);policy(a,true,32);policy(b,true,32);
        when(authentication.authenticate(anyString(),anyString())).thenThrow(new UnauthorizedException("invalid"));doReturn(a).when(authentication).authenticate(a.instanceKey(),"synthetic-only");}
    RuntimeConnectionService.Access identity(){return new TransactionTemplate(transactions).execute(tx->{
        var agent=agents.create(new AgentDtos.AgentRequest("SCOPE-"+UUID.randomUUID(),"Scope test","","Test","Owner","Test",RiskLevel.LOW));agents.updateStatus(agent.id(),AgentLifecycleStatus.ACTIVE);
        var r=new AgentRuntimeInstance();r.agentId=agent.id();r.instanceKey="eap-agents/scope-"+agent.id();r.endpoint="substrate://"+r.instanceKey;r.protocol="SUBSTRATE_OBSERVED";r.importedActorUid="uid-"+UUID.randomUUID();r.importedActorTemplate="eap-agents/synthetic";runtimes.saveAndFlush(r);
        var c=new RuntimeConnection();c.instanceKey=r.instanceKey;c.generation=UUID.randomUUID().toString();c.verifiedAt=Instant.now();c.expiresAt=Instant.now().plusSeconds(3600);connections.saveAndFlush(c);
        return new RuntimeConnectionService.Access(r.instanceKey,agent.id(),c.generation,"runtime-"+r.id);
    });}
    void policy(RuntimeConnectionService.Access id,boolean enabled,long limit){var p=policies.get(id.instanceKey());policies.update(new RuntimeToolPolicyService.Update(id.instanceKey(),p.revision(),enabled,List.of(new RuntimeToolPolicyService.Tool(ScopedToolService.TOOL,ToolInvocationKind.TOOL)),limit),"test");}
    ScopedToolDtos.Grant root(long cap){return service.create(new ScopedToolDtos.Root(UUID.randomUUID().toString(),a.instanceKey(),List.of(resource,other),List.of(a.instanceKey(),b.instanceKey()),10,cap,expires),"test");}
    ScopedToolDtos.Grant child(ScopedToolDtos.Grant p,long cap){return service.delegate(a,new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),5,cap,expires.minusSeconds(60)));}
    ScopedToolDtos.Execute request(String grant,long delta){return new ScopedToolDtos.Execute(grant,UUID.randomUUID().toString(),resource,delta);}
    long value(){return db.queryForObject("select counter_value from scoped_tool_counters where resource_id=?",Long.class,resource);}
    long effects(){return db.queryForObject("select effect_count from scoped_tool_counters where resource_id=?",Long.class,resource);}
    @Test void validatedCounterIdentifiersAreNotMisclassifiedAsPhoneContent(){
        String opaque="11111111-aaaa-4aaa-aaaa-13800138000a";resource="poc_"+opaque;
        var root=root(1);var request=new ScopedToolDtos.Execute(root.grantId(),opaque,resource,1);
        var result=service.execute(a,request);
        assertThat(result.state()).as("reason=%s",result.reasonCode()).isEqualTo("COMMITTED");
        assertThat(result.effectSource()).isEqualTo("TOOL_STORE_VERIFIED");assertThat(effects()).isEqualTo(1);
        assertThat(service.execute(a,request).effectId()).isEqualTo(result.effectId());assertThat(value()).isEqualTo(1);
    }
    @Test void realStoreCommitAndReplayHaveOneEffectAndChargeEachDistinctAuthorityOnce(){
        var r=root(2);var c=child(r,2);var request=request(c.grantId(),3);var result=service.execute(b,request);
        assertThat(result.state()).isEqualTo("COMMITTED");assertThat(result.effectSource()).isEqualTo("TOOL_STORE_VERIFIED");assertThat(result.effectId()).isNotBlank();assertThat(value()).isEqualTo(3);assertThat(effects()).isEqualTo(1);
        for(int i=0;i<4;i++)assertThat(service.execute(b,request).effectId()).isEqualTo(result.effectId());
        assertThat(policies.get(a.instanceKey()).claimedCount()).isEqualTo(1);assertThat(policies.get(b.instanceKey()).claimedCount()).isEqualTo(1);
        assertThat(service.dashboard(a.instanceKey()).grants()).allMatch(g->g.usedCount()==1);
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='TOOL_CLAIMS'",Long.class,"tool-root:"+r.grantId())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from budget_reservation_items i join budget_reservations r on i.reservation_id=r.id where r.task_key=?",Long.class,"tool-root:"+r.grantId())).isEqualTo(4);
        assertThat(service.query(b,r.grantId(),request.operationId()).effectId()).isEqualTo(result.effectId());
    }
    @Test void workflowStoreVerificationBindsHolderArgumentsAndEffectWithoutReplay(){
        var c=child(root(2),2);var req=request(c.grantId(),3);var o=service.execute(b,req);
        var output=json.valueToTree(Map.of("rootId",o.rootId(),"operationId",o.operationId(),"effectId",o.effectId(),"counterValue",o.counterValue(),"evidenceSource",o.effectSource()));
        assertThat(service.verifyEffect(b.instanceKey(),req,output)).isTrue();
        assertThat(service.verifyEffect(a.instanceKey(),req,output)).isFalse();
        assertThat(service.verifyEffect(b.instanceKey(),new ScopedToolDtos.Execute(req.grantId(),req.operationId(),resource,4),output)).isFalse();
        for(var field:List.of("effectId","rootId","operationId","evidenceSource","counterValue")){
            var forged=((com.fasterxml.jackson.databind.node.ObjectNode)output).deepCopy();forged.put(field,"forged");assertThat(service.verifyEffect(b.instanceKey(),req,forged)).isFalse();
        }
        service.revoke(o.rootId(),"test");assertThat(service.verifyEffect(b.instanceKey(),req,output)).isTrue();
        assertThat(value()).isEqualTo(3);assertThat(effects()).isEqualTo(1);
    }
    @Test void exhaustedLeafDoesNotMaskAncestorRevisionAndRevocation(){
        var r=root(1);var c=child(r,1);service.execute(b,request(c.grantId(),1));policy(a,true,32);
        assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("TOOL_POLICY_CHANGED");
        service.revoke(r.grantId(),"test");assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("GRANT_REVOKED");
        assertThat(effects()).isEqualTo(1);
    }
    @Test void childCannotWidenResourcesRecipientsArgumentsLimitsOrExpiry(){
        var p=root(2);var baseline=new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),5,2,expires.minusSeconds(60));
        for(var changed:List.of(
            new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),List.of("poc_unapproved"),baseline.recipientSet(),5,2,baseline.expiresAt()),
            new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),baseline.resourceSet(),List.of("eap-agents/unknown"),5,2,baseline.expiresAt()),
            new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),baseline.resourceSet(),baseline.recipientSet(),11,2,baseline.expiresAt()),
            new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),baseline.resourceSet(),baseline.recipientSet(),5,3,baseline.expiresAt()),
            new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),p.grantId(),b.instanceKey(),baseline.resourceSet(),baseline.recipientSet(),5,2,expires.plusSeconds(1))))assertThatThrownBy(()->service.delegate(a,changed)).isInstanceOfAny(BusinessException.class,UnauthorizedException.class);
        assertThat(service.dashboard(a.instanceKey()).grants()).hasSize(1);
    }
    @Test void rootAndDelegateRequestKeysAreImmutable(){
        var id=UUID.randomUUID().toString();var request=new ScopedToolDtos.Root(id,a.instanceKey(),List.of(resource),List.of(a.instanceKey(),b.instanceKey()),10,2,expires);
        var r=service.create(request,"test");assertThat(service.create(request,"test").grantId()).isEqualTo(id);
        assertThatThrownBy(()->service.create(request,"other-subject")).isInstanceOf(ConflictException.class);
        var d=new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),r.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),5,2,expires.minusSeconds(60));service.delegate(a,d);service.delegate(a,d);
        assertThatThrownBy(()->service.delegate(a,new ScopedToolDtos.Delegate(d.grantId(),r.grantId(),b.instanceKey(),d.resourceSet(),d.recipientSet(),4,2,d.expiresAt()))).isInstanceOf(ConflictException.class);
    }
    @Test void operationCannotChangeParentGrantInstanceOrArguments(){
        var r=root(3);var c=child(r,2);var req=request(c.grantId(),1);service.execute(b,req);
        assertThatThrownBy(()->service.execute(b,new ScopedToolDtos.Execute(c.grantId(),req.operationId(),resource,2))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.execute(a,new ScopedToolDtos.Execute(r.grantId(),req.operationId(),resource,1))).isInstanceOf(ConflictException.class);
        assertThat(effects()).isEqualTo(1);
    }
    @Test void parameterScopeDenialHasIndependentNoCommitProofAndNoConsumption(){
        var r=root(2);var c=child(r,2);for(var req:List.of(request(c.grantId(),6),new ScopedToolDtos.Execute(c.grantId(),UUID.randomUUID().toString(),other,1))){var result=service.execute(b,req);assertThat(result.state()).isEqualTo("REJECTED");assertThat(result.effectSource()).isEqualTo("TOOL_STORE_NO_COMMIT");assertThat(result.reasonCode()).isEqualTo("PARAMETER_SCOPE_DENIED");}
        assertThat(value()).isZero();assertThat(effects()).isZero();assertThat(policies.get(a.instanceKey()).claimedCount()).isZero();
    }
    @Test void revocationBeforeCommitBlocksDescendantsAndAfterCommitPreservesEffect(){
        var r=root(3);var c=child(r,3);var first=request(c.grantId(),2);var effect=service.execute(b,first);service.revoke(r.grantId(),"test");
        assertThat(service.execute(b,first).effectId()).isEqualTo(effect.effectId());var denied=service.execute(b,request(c.grantId(),2));assertThat(denied.reasonCode()).isEqualTo("GRANT_REVOKED");assertThat(value()).isEqualTo(2);assertThat(effects()).isEqualTo(1);
    }
    @Test void changingAnyAncestorPolicyFencesOldDelegation(){var r=root(2);var c=child(r,2);policy(a,true,33);assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("TOOL_POLICY_CHANGED");assertThat(effects()).isZero();}
    @Test void localRecipientPolicyIsRequiredAndCannotBeBypassedByParent(){var r=root(2);policy(b,false,32);assertThatThrownBy(()->child(r,2)).isInstanceOf(BusinessException.class);assertThat(effects()).isZero();}
    @Test void oldActorIdentityAndCredentialGenerationInvalidateChain(){
        var r=root(2);var c=child(r,2);new TransactionTemplate(transactions).executeWithoutResult(tx->{var row=connections.findByInstanceKey(a.instanceKey()).orElseThrow();row.generation=UUID.randomUUID().toString();connections.saveAndFlush(row);});assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("INSTANCE_CHANGED");assertThat(effects()).isZero();
    }
    @Test void expiryAndContractChangesAreCheckedAtCommit(){var r=root(2);var c=child(r,2);db.update("update scoped_tool_grants set expires_at=? where grant_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),r.grantId());assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("GRANT_EXPIRED");db.update("update scoped_tool_grants set expires_at=?,contract_version='unknown' where grant_id=?",java.sql.Timestamp.from(expires),r.grantId());assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("CONTRACT_CHANGED");assertThat(effects()).isZero();}
    @Test void siblingsAndNewOperationIdsShareRootLastCredit()throws Exception{
        var r=root(1);var c1=child(r,1);var c2=child(r,1);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)){var results=new ArrayList<Future<ScopedToolDtos.Operation>>();for(int i=0;i<8;i++){var req=request(i%2==0?c1.grantId():c2.grantId(),1);results.add(pool.submit(()->{start.await();return service.execute(b,req);}));}start.countDown();long commits=0;for(var f:results){var op=f.get(30,TimeUnit.SECONDS);if(op.state().equals("COMMITTED"))commits++;else assertThat(op.reasonCode()).isEqualTo("TOOL_BUDGET_EXHAUSTED");}assertThat(commits).isEqualTo(1);}
        assertThat(effects()).isEqualTo(1);assertThat(policies.get(a.instanceKey()).claimedCount()).isEqualTo(1);
    }
    @Test void duplicateConcurrentDeliveryOnlyCommitsOnce()throws Exception{
        var r=root(8);var request=request(r.grantId(),1);try(var pool=Executors.newFixedThreadPool(8)){var results=new ArrayList<Future<ScopedToolDtos.Operation>>();for(int i=0;i<8;i++)results.add(pool.submit(()->service.execute(a,request)));for(var f:results)assertThat(f.get(30,TimeUnit.SECONDS).state()).isIn("COMMITTED","UNKNOWN");}assertThat(effects()).isEqualTo(1);
    }
    @Test void sharedBudgetRejectRollsBackAuthorityConsumptionAndCounterEffect(){var r=root(2);var c=child(r,2);budget.setLimit(new BudgetLedger.Limit("AGENT",a.agentId().toString(),"TOOL_CLAIMS",0L,0,0),"test");var op=service.execute(b,request(c.grantId(),1));assertThat(op.reasonCode()).isEqualTo("SHARED_BUDGET_EXHAUSTED");assertThat(effects()).isZero();assertThat(policies.get(a.instanceKey()).claimedCount()).isZero();assertThat(policies.get(b.instanceKey()).claimedCount()).isZero();assertThat(service.dashboard(a.instanceKey()).grants()).allMatch(g->g.usedCount()==0);}
    @Test void rootLedgerLimitCannotBeIncreasedOrReset(){var r=root(2);assertThatThrownBy(()->budget.setLimit(new BudgetLedger.Limit("TASK","tool-root:"+r.grantId(),"TOOL_CLAIMS",99L,0,1),"test")).isInstanceOf(ConflictException.class);}
    @Test void failureAfterCounterMutationRollsBackAndPersistentUnknownNeverReplays(){
        var r=root(2);var request=request(r.grantId(),1);
        RuntimeToolGuardService target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(guard);
        doThrow(new IllegalStateException("synthetic storage failure")).when(target).recordVerified(any(),anyString(),anyString(),anyString(),anyString());
        try{assertThatThrownBy(()->service.execute(a,request)).isInstanceOf(IllegalStateException.class);}finally{doCallRealMethod().when(target).recordVerified(any(),anyString(),anyString(),anyString(),anyString());}
        assertThat(service.query(a,r.grantId(),request.operationId()).state()).isEqualTo("UNKNOWN");assertThat(service.execute(a,request).state()).isEqualTo("UNKNOWN");assertThat(effects()).isZero();assertThat(policies.get(a.instanceKey()).claimedCount()).isZero();
    }
    @Test void runtimeReceiptCannotForgeIndependentEffect(){
        var r=root(2);var req=request(r.grantId(),1);var args=json.valueToTree(Map.of("grantId",r.grantId(),"operationId",req.operationId(),"resourceId",resource,"delta",1));guard.check(a,new RuntimeToolDtos.Check(req.operationId(),ScopedToolService.TOOL,ToolInvocationKind.TOOL,"11111111111111111111111111111111",args));
        assertThatThrownBy(()->guard.receipt(a,new RuntimeToolDtos.Receipt(req.operationId(),"SUCCEEDED",0L,null))).isInstanceOf(ConflictException.class);assertThat(effects()).isZero();
    }
    @Test void machineRequestsRejectConsoleIdentityAndForgedEvidenceFields()throws Exception{
        var r=root(2);String path="/api/runtime-access/v1/tools/scoped/execute";var req=request(r.grantId(),1);var body=json.valueToTree(req);
        mvc.perform(post(path).contentType("application/json").content(body.toString())).andExpect(status().isUnauthorized());
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("effectSource","TOOL_STORE_VERIFIED");mvc.perform(post(path).contentType("application/json").content(body.toString()).header("X-EAP-Runtime-Key",a.instanceKey()).header("X-EAP-Runtime-Token","synthetic-only")).andExpect(status().isBadRequest());assertThat(effects()).isZero();
    }
    @Test void forgedParentAndExcessDelegationDepthCannotAcquireAuthority(){
        var r=root(2);var c=child(r,2);
        assertThatThrownBy(()->service.delegate(b,new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),r.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),1,1,expires.minusSeconds(100)))).isInstanceOf(UnauthorizedException.class);
        for(int i=0;i<3;i++)c=service.delegate(b,new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),c.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),1,1,c.expiresAt()));
        var last=c;assertThatThrownBy(()->service.delegate(b,new ScopedToolDtos.Delegate(UUID.randomUUID().toString(),last.grantId(),b.instanceKey(),List.of(resource),List.of(b.instanceKey()),1,1,last.expiresAt()))).isInstanceOf(BusinessException.class);
    }
    @Test void actorUidReplacementDoesNotReuseOldGrant(){var r=root(2);var c=child(r,2);new TransactionTemplate(transactions).executeWithoutResult(tx->{var row=runtimes.findByInstanceKey(a.instanceKey()).orElseThrow();row.importedActorUid="replacement";runtimes.saveAndFlush(row);});assertThat(service.execute(b,request(c.grantId(),1)).reasonCode()).isEqualTo("INSTANCE_CHANGED");assertThat(effects()).isZero();}
    @Test void queryIsReadOnlyAndDoesNotLetAnotherInstanceReadTheOperation(){var r=root(2);var c=child(r,2);var req=request(c.grantId(),1);service.execute(b,req);assertThatThrownBy(()->service.query(a,r.grantId(),req.operationId())).isInstanceOf(UnauthorizedException.class);assertThat(effects()).isEqualTo(1);}
    @Test void rootCannotReuseObjectsBelongingToAnotherAuthorization(){root(2);assertThatThrownBy(()->root(2)).isInstanceOf(ConflictException.class);assertThat(service.dashboard(a.instanceKey()).grants()).hasSize(1);}
}
