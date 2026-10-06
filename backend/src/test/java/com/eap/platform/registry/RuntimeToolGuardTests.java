package com.eap.platform.registry;

import com.eap.platform.agent.*;
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
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RuntimeToolGuardTests {
    @Autowired RuntimeToolGuardService guard;
    @Autowired com.eap.platform.budget.BudgetLedger budgets;
    @Autowired RuntimeToolPolicyService policies;
    @Autowired RuntimeConnectionRepository connections;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired AgentService agents;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @MockitoBean RuntimeConnectionService authentication;
    RuntimeConnectionService.Access access;

    @BeforeEach void setup(){
        new TransactionTemplate(transactions).executeWithoutResult(tx->{
            var agent=agents.create(new AgentDtos.AgentRequest("GUARD-"+UUID.randomUUID(),"Guard test","","Test","Owner","Test",RiskLevel.LOW));
            agents.updateStatus(agent.id(),AgentLifecycleStatus.ACTIVE);
            var r=new AgentRuntimeInstance();r.agentId=agent.id();r.instanceKey="eap-agents/tool-"+agent.id();r.endpoint="substrate://"+r.instanceKey;
            r.protocol="SUBSTRATE_OBSERVED";r.importedActorUid="synthetic-uid";r.importedActorTemplate="eap-agents/synthetic";runtimes.saveAndFlush(r);
            var c=new RuntimeConnection();c.instanceKey=r.instanceKey;c.generation=UUID.randomUUID().toString();c.verifiedAt=Instant.now();c.expiresAt=Instant.now().plusSeconds(3600);connections.saveAndFlush(c);
            access=new RuntimeConnectionService.Access(r.instanceKey,agent.id(),c.generation,"runtime-"+r.id);
        });
        policy(true,100, "lookup","read_file","delete_record");
        when(authentication.authenticate(anyString(),anyString())).thenThrow(new UnauthorizedException("invalid"));
        doReturn(access).when(authentication).authenticate(access.instanceKey(),"synthetic-only");
    }
    @Test void sharedBudgetRejectionRollsBackPolicyConsumption()throws Exception {
        budgets.setLimit(new com.eap.platform.budget.BudgetLedger.Limit("AGENT",access.agentId().toString(),"TOOL_CLAIMS",1L,0,0),"test");
        var first=request("lookup","{}");guard.check(access,first);assertThat(guard.claim(access,new RuntimeToolDtos.Claim(first.callId())).execute()).isTrue();
        var next=request("lookup","{}");guard.check(access,next);
        assertThatThrownBy(()->guard.claim(access,new RuntimeToolDtos.Claim(next.callId()))).isInstanceOf(com.eap.platform.gateway.GatewayException.class);
        assertThat(policies.get(access.instanceKey()).claimedCount()).isEqualTo(1);
        assertThat(guard.check(access,next).state()).isEqualTo("APPROVED");
    }
    RuntimeToolPolicyService.View policy(boolean enabled,long limit,String... names){
        var before=policies.get(access.instanceKey());
        return policies.update(new RuntimeToolPolicyService.Update(access.instanceKey(),before.revision(),enabled,
            Arrays.stream(names).map(n->new RuntimeToolPolicyService.Tool(n,ToolInvocationKind.TOOL)).toList(),limit),"policy-test");
    }
    RuntimeConnectionService.Access secondRuntime(){
        return new TransactionTemplate(transactions).execute(tx->{
            var r=new AgentRuntimeInstance();r.agentId=access.agentId();r.instanceKey=access.instanceKey()+"-second";r.endpoint="substrate://"+r.instanceKey;
            r.protocol="SUBSTRATE_OBSERVED";r.importedActorUid="synthetic-second";r.importedActorTemplate="eap-agents/synthetic";runtimes.saveAndFlush(r);
            var c=new RuntimeConnection();c.instanceKey=r.instanceKey;c.generation=UUID.randomUUID().toString();c.verifiedAt=Instant.now();c.expiresAt=Instant.now().plusSeconds(3600);connections.saveAndFlush(c);
            return new RuntimeConnectionService.Access(r.instanceKey,access.agentId(),c.generation,"runtime-"+r.id);
        });
    }
    RuntimeToolDtos.Check request(String name,String args)throws Exception{
        return new RuntimeToolDtos.Check(UUID.randomUUID().toString(),name,ToolInvocationKind.TOOL,"11111111111111111111111111111111",json.readTree(args));
    }
    void changeConnection(java.util.function.Consumer<RuntimeConnection> change){
        new TransactionTemplate(transactions).executeWithoutResult(tx->{var c=connections.findByInstanceKey(access.instanceKey()).orElseThrow();change.accept(c);connections.saveAndFlush(c);});
    }
    @Test void unconfiguredToolScopeCannotAuthorizeExecution() throws Exception {
        jdbc.update("delete from runtime_tool_policies where agent_id=?",access.agentId());
        var result=guard.check(access,request("lookup","{}"));
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo("TOOL_POLICY_MISSING");
    }
    @Test void exactScopeIncludesToolKindAndUnapprovedChecksDoNotInspectPayload()throws Exception{
        var denied=guard.check(access,request("lookup_extra","{}"));
        assertThat(denied.reasonCode()).isEqualTo("TOOL_SCOPE_DENIED");
        assertThat(denied.safetyDecision()).isEqualTo("NOT_EVALUATED");
        assertThat(jdbc.queryForObject("select count(*) from safety_events where session_key=?",Long.class,denied.decisionKey())).isZero();
        var req=request("lookup","{}");
        var mcp=new RuntimeToolDtos.Check(req.callId(),req.toolName(),ToolInvocationKind.MCP,req.traceId(),req.arguments());
        assertThat(guard.check(access,mcp).state()).isEqualTo("BLOCKED");
    }
    @Test void revocationAndVersionFenceInvalidateAlreadyApprovedChecks()throws Exception{
        var a=request("lookup","{}");guard.check(access,a);policy(false,100,"lookup");
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(a.callId())).call().reasonCode()).isEqualTo("TOOL_POLICY_DISABLED");
        policy(true,100,"lookup");var b=request("lookup","{}");guard.check(access,b);
        policy(true,101,"lookup");
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(b.callId())).call().reasonCode()).isEqualTo("TOOL_POLICY_CHANGED");
        assertThat(policies.get(access.instanceKey()).claimedCount()).isZero();
    }
    @Test void concurrentInstancesShareOneBudgetAndUnknownOrFailedEffectsNeverRefund()throws Exception{
        policy(true,1,"lookup");var second=secondRuntime();
        var identities=List.of(access,second,access,second);var requests=new ArrayList<RuntimeToolDtos.Check>();
        for(var identity:identities){var req=request("lookup","{}");requests.add(req);assertThat(guard.check(identity,req).state()).isEqualTo("APPROVED");}
        var start=new CountDownLatch(1);int winner=-1;
        try(var pool=Executors.newFixedThreadPool(4)){
            var results=new ArrayList<Future<RuntimeToolDtos.ClaimResult>>();
            for(int i=0;i<4;i++){final int n=i;results.add(pool.submit(()->{start.await();return guard.claim(identities.get(n),new RuntimeToolDtos.Claim(requests.get(n).callId()));}));}
            start.countDown();int allowed=0;
            for(int i=0;i<4;i++){var result=results.get(i).get(30,TimeUnit.SECONDS);if(result.execute()){allowed++;winner=i;}else assertThat(result.call().reasonCode()).isEqualTo("TOOL_BUDGET_EXHAUSTED");}
            assertThat(allowed).isEqualTo(1);
        }
        assertThat(policies.get(second.instanceKey()).remainingClaims()).isZero();
        assertThat(policies.get(access.instanceKey()).claimedCount()).isEqualTo(1);
        var retry=guard.claim(identities.get(winner),new RuntimeToolDtos.Claim(requests.get(winner).callId()));assertThat(retry.execute()).isFalse();
        guard.receipt(identities.get(winner),new RuntimeToolDtos.Receipt(requests.get(winner).callId(),"FAILED",2L,null));
        assertThat(policies.get(access.instanceKey()).claimedCount()).isEqualTo(1);
        assertThatThrownBy(()->policy(true,0,"lookup")).isInstanceOf(BusinessException.class);
    }
    @Test void newCredentialCannotResetSharedBudgetAndIncreasingCeilingPreservesUsage()throws Exception{
        policy(true,1,"lookup");var a=request("lookup","{}");guard.check(access,a);guard.claim(access,new RuntimeToolDtos.Claim(a.callId()));
        String generation=UUID.randomUUID().toString();changeConnection(c->c.generation=generation);
        var next=new RuntimeConnectionService.Access(access.instanceKey(),access.agentId(),generation,access.endpointKey());
        var b=request("lookup","{}");guard.check(next,b);
        assertThat(guard.claim(next,new RuntimeToolDtos.Claim(b.callId())).call().reasonCode()).isEqualTo("TOOL_BUDGET_EXHAUSTED");
        policy(true,2,"lookup");assertThat(policies.get(access.instanceKey()).claimedCount()).isEqualTo(1);
        var c=request("lookup","{}");guard.check(next,c);assertThat(guard.claim(next,new RuntimeToolDtos.Claim(c.callId())).execute()).isTrue();
    }
    @Test void concurrentPolicyEditsHaveOneWinnerAndPersistAuditedRevision()throws Exception{
        var before=policies.get(access.instanceKey());var second=secondRuntime();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var futures=new ArrayList<Future<Boolean>>();
            for(var identity:List.of(access,second))futures.add(pool.submit(()->{start.await();try{
                policies.update(new RuntimeToolPolicyService.Update(identity.instanceKey(),before.revision(),false,List.of(),100),"governance-test");return true;
            }catch(ConflictException e){return false;}}));
            start.countDown();int wins=0;for(var f:futures)if(f.get(30,TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
        }
        assertThat(policies.get(access.instanceKey()).revision()).isEqualTo(before.revision()+1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where resource_id=? and action='RUNTIME_TOOL_POLICY_UPDATED' and actor='governance-test'",Long.class,access.agentId().toString())).isEqualTo(1);
    }
    @Test void checkedArgumentsAreStableAndReceiptCannotRewriteHistory()throws Exception{
        var request=request("lookup","{\"b\":2,\"a\":1}");var first=guard.check(access,request);
        var replay=new RuntimeToolDtos.Check(request.callId(),request.toolName(),request.toolKind(),request.traceId(),json.readTree("{\"a\":1,\"b\":2}"));
        assertThat(guard.check(access,replay).decisionKey()).isEqualTo(first.decisionKey());
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(request.callId())).execute()).isTrue();
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(request.callId())).execute()).isFalse();
        var receipt=new RuntimeToolDtos.Receipt(request.callId(),"SUCCEEDED",4L,null);
        assertThat(guard.receipt(access,receipt).state()).isEqualTo("SUCCEEDED");
        assertThat(guard.receipt(access,receipt).completedAt()).isNotNull();
        assertThatThrownBy(()->guard.receipt(access,new RuntimeToolDtos.Receipt(request.callId(),"FAILED",4L,null))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from tool_invocation_events where runtime_instance_key=?",Long.class,access.instanceKey())).isEqualTo(1L);
    }
    @Test void ordinaryCounterCheckCannotRequestAnOpaqueIdentifierExemption()throws Exception{
        policy(true,2,ScopedToolService.TOOL);
        var result=guard.check(access,request(ScopedToolService.TOOL,"{\"operationId\":\"11111111-aaaa-4aaa-aaaa-13800138000a\"}"));
        assertThat(result.state()).isNotEqualTo("APPROVED");
        assertThat(result.reasonCode()).startsWith("DATA_");
    }
    @Test void ordinaryCounterCheckStillInspectsSecretKeys()throws Exception{
        policy(true,2,ScopedToolService.TOOL);
        var result=guard.check(access,request(ScopedToolService.TOOL,"{\"password\":\"synthetic-sensitive-marker\"}"));
        assertThat(result.state()).isEqualTo("BLOCKED");assertThat(result.safetyDecision()).isEqualTo("DENY");
    }
    @Test void secretArgumentsAreBlockedAndNeverPersistedAsSafetyPayload()throws Exception{
        var request=request("lookup","{\"password\":\"synthetic-sensitive-marker\"}");var result=guard.check(access,request);
        assertThat(result.state()).isEqualTo("BLOCKED");assertThat(result.safetyDecision()).isEqualTo("DENY");
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(request.callId())).execute()).isFalse();
        assertThatThrownBy(()->guard.receipt(access,new RuntimeToolDtos.Receipt(request.callId(),"SUCCEEDED",0L,null))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("select payload_json from safety_events where session_key=?",String.class,result.decisionKey())).isNull();
        assertThat(jdbc.queryForObject("select payload_summary from safety_events where session_key=?",String.class,result.decisionKey())).doesNotContain("synthetic-sensitive-marker");
        assertThat(jdbc.queryForObject("select count(*) from tool_invocation_events where runtime_instance_key=?",Long.class,access.instanceKey())).isZero();
    }
    @Test void privilegedPathsAreInspectedButNotPersisted()throws Exception{
        var result=guard.check(access,request("read_file","{\"path\":\"/etc/synthetic-private-file\"}"));
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("select target from safety_events where session_key=?",String.class,result.decisionKey())).isEqualTo("tool://read_file");
    }
    @Test void highRiskToolsRequireReviewAndChangedArgumentsCannotReuseApproval()throws Exception{
        var risky=request("delete_record","{}");assertThat(guard.check(access,risky).state()).isEqualTo("REVIEW_REQUIRED");
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(risky.callId())).execute()).isFalse();
        var safe=request("lookup","{\"id\":1}");guard.check(access,safe);
        var changed=new RuntimeToolDtos.Check(safe.callId(),safe.toolName(),safe.toolKind(),safe.traceId(),json.readTree("{\"id\":2}"));
        assertThatThrownBy(()->guard.check(access,changed)).isInstanceOf(ConflictException.class);
    }
    @Test void expiredGrantRotationAndRevocationNeverPermitNewClaims()throws Exception{
        var request=request("lookup","{}");guard.check(access,request);
        jdbc.update("update runtime_tool_calls set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),access.instanceKey());
        assertThat(guard.claim(access,new RuntimeToolDtos.Claim(request.callId())).execute()).isFalse();
        changeConnection(c->c.generation="new-generation");
        assertThatThrownBy(()->guard.claim(access,new RuntimeToolDtos.Claim(request.callId()))).isInstanceOf(UnauthorizedException.class);
        var rotated=new RuntimeConnectionService.Access(access.instanceKey(),access.agentId(),"new-generation",access.endpointKey());
        assertThatThrownBy(()->guard.check(rotated,request)).isInstanceOf(ConflictException.class);
        changeConnection(c->c.generation=null);
        assertThatThrownBy(()->guard.check(rotated,request)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void concurrentFirstChecksHaveOneDecisionAndClaimsGrantExactlyOnce()throws Exception{
        var request=request("lookup","{}");
        try(var pool=Executors.newFixedThreadPool(4)){
            var checks=new ArrayList<Future<RuntimeToolDtos.View>>();for(int i=0;i<4;i++)checks.add(pool.submit(()->guard.check(access,request)));
            var keys=new HashSet<String>();for(var f:checks)keys.add(f.get(30,TimeUnit.SECONDS).decisionKey());assertThat(keys).hasSize(1);
            var claims=new ArrayList<Future<Boolean>>();for(int i=0;i<4;i++)claims.add(pool.submit(()->guard.claim(access,new RuntimeToolDtos.Claim(request.callId())).execute()));
            int allowed=0;for(var f:claims)if(f.get(30,TimeUnit.SECONDS))allowed++;assertThat(allowed).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from runtime_tool_calls where instance_key=?",Long.class,access.instanceKey())).isEqualTo(1L);
    }
    @Test void machineApiRejectsMissingCredentialsForgedAttributionAndUnknownFields()throws Exception{
        String path="/api/runtime-access/v1/tools/check",body=json.writeValueAsString(request("lookup","{}"));
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).contentType("application/json").content(body).header("X-EAP-Runtime-Key",access.instanceKey()).header("X-EAP-Runtime-Token","synthetic-only").header("X-EAP-Agent-Id","99999")).andExpect(status().isBadRequest());
        var spoof=json.readTree(body);((com.fasterxml.jackson.databind.node.ObjectNode)spoof).put("agentId",99999);
        mvc.perform(post(path).contentType("application/json").content(spoof.toString()).header("X-EAP-Runtime-Key",access.instanceKey()).header("X-EAP-Runtime-Token","synthetic-only")).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType("application/json").content(body).header("X-EAP-Runtime-Key",access.instanceKey()).header("X-EAP-Runtime-Token","synthetic-only")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("APPROVED"));
    }
}
