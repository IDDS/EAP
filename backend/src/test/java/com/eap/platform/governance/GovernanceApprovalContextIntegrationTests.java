package com.eap.platform.governance;

import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.eap.platform.governance.GovernancePolicyDtos.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class GovernanceApprovalContextIntegrationTests {
    @Autowired GovernancePolicyService policies;
    @Autowired GovernancePreflightService preflight;
    @Autowired GovernancePreflightDecisionRepository decisions;
    @Autowired GovernanceDataProperties settings;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    final List<Long> policyIds=new ArrayList<>();
    final List<String> keys=new ArrayList<>();
    final String model="approval-context-"+UUID.randomUUID();

    PolicyRequest policy(String name, boolean enabled, Long revision) {
        return new PolicyRequest(name,PolicyType.PRIVACY,"MODEL",model,"{\"sensitivity\":\"PUBLIC\"}",EnforcementMode.APPROVAL_REQUIRED,enabled,revision);
    }
    PolicyResponse createPolicy(boolean enabled) {
        var result=policies.create(policy("PUBLIC-approval-context",enabled,null));policyIds.add(result.id());return result;
    }
    GovernanceDataDtos.PreflightRequest request(String key) {
        return new GovernanceDataDtos.PreflightRequest(key,"event-"+key,db.queryForObject("select min(id) from agents",Long.class),null,null,"b".repeat(32),
                GovernanceDataDirection.INPUT_TO_MODEL,"PUBLIC_FIXTURE",GovernanceDataSensitivity.PUBLIC,false,false,0,0,"fixture","PUBLIC",model,List.of(),null,Instant.now());
    }
    GovernanceDataDtos.PreflightResponse evaluate() {
        String key="ctx-"+UUID.randomUUID();keys.add(key);return preflight.evaluate(request(key));
    }
    GovernanceDataDtos.ApprovalRequest approval() {return new GovernanceDataDtos.ApprovalRequest("PUBLIC-reviewer","public fixture");}
    GovernanceDataDtos.PreflightResponse reuse(GovernanceDataDtos.PreflightResponse decision) {
        return preflight.reusableDecision(decision.decisionKey(),request(decision.decisionKey()).agentId(),null,"PUBLIC",model,decision.requestFingerprint());
    }
    Map<String,Object> stored(String key) {return db.queryForMap("select * from governance_preflight_decisions where decision_key=?",key);}
    @AfterEach void cleanup() {
        for(String key:keys) {
            db.update("delete from audit_logs where resource_id=?",key);
            db.update("delete from governance_preflight_decisions where decision_key=?",key);
            db.update("delete from governance_data_events where event_key=?","event-"+key);
        }
        for(Long id:policyIds) {db.update("delete from governance_policy_versions where policy_id=?",id);db.update("delete from governance_policies where id=?",id);}
    }
    @Test void currentContextApprovesAndReusesOnlyTheOriginalRequestWithoutChangingReads() {
        createPolicy(true);var initial=evaluate();assertThat(initial.approvalContextState()).isEqualTo("CURRENT");
        var response=preflight.approve(initial.decisionKey(),approval());
        assertThat(response.approvalStatus()).isEqualTo(GovernanceApprovalStatus.APPROVED);
        // Compare durable reads at the database's timestamp precision, not the transient nanosecond value.
        var approved=preflight.get(initial.decisionKey());var before=stored(initial.decisionKey());
        assertThat(reuse(approved)).isEqualTo(approved);assertThat(preflight.get(initial.decisionKey())).isEqualTo(approved);
        assertThat(stored(initial.decisionKey())).isEqualTo(before);
        assertThatThrownBy(()->preflight.reusableDecision(approved.decisionKey(),request(approved.decisionKey()).agentId(),null,"PUBLIC",model,"different"))
                .hasMessageContaining("请求内容不一致");
    }
    @Test void editedPolicyBlocksPendingApprovalWithoutRewritingOriginalDecision() {
        var p=createPolicy(true);var initial=evaluate();var before=stored(initial.decisionKey());
        policies.update(p.id(),policy("changed",true,0L));
        assertThatThrownBy(()->preflight.approve(initial.decisionKey(),approval())).isInstanceOf(ConflictException.class).hasMessageContaining("已变更");
        var current=preflight.get(initial.decisionKey());assertThat(current.approvalStatus()).isEqualTo(GovernanceApprovalStatus.PENDING);
        assertThat(current.approvalContextState()).isEqualTo("CHANGED");assertThat(stored(initial.decisionKey())).isEqualTo(before);
    }
    @Test void disablingAPolicyBlocksApprovedReuseButPreservesTheHistoricalApproval() {
        var p=createPolicy(true);var approved=preflight.approve(evaluate().decisionKey(),approval());var before=stored(approved.decisionKey());
        policies.update(p.id(),policy(p.name(),false,0L));
        assertThatThrownBy(()->reuse(approved)).isInstanceOf(ConflictException.class);
        var read=preflight.get(approved.decisionKey());assertThat(read.approvalStatus()).isEqualTo(GovernanceApprovalStatus.APPROVED);
        assertThat(read.requestAllowed()).isTrue();assertThat(read.approvalContextState()).isEqualTo("CHANGED");
        assertThat(stored(approved.decisionKey())).isEqualTo(before);
    }
    @Test void newlyEnabledEvenNonmatchingPolicyInvalidatesConservativeGlobalApprovalContext() {
        createPolicy(true);var p=policies.create(new PolicyRequest("nonmatching",PolicyType.PRIVACY,"MODEL","other-model","{}",EnforcementMode.BLOCK,false,null));policyIds.add(p.id());
        var approved=preflight.approve(evaluate().decisionKey(),approval());
        policies.update(p.id(),new PolicyRequest(p.name(),p.policyType(),p.scopeType(),p.scopeValue(),p.ruleJson(),p.enforcementMode(),true,0L));
        assertThatThrownBy(()->reuse(approved)).isInstanceOf(ConflictException.class);
    }
    @Test void aNewPolicyIsDetectedEvenWhenItDidNotExistInTheOriginalMatches() {
        createPolicy(true);var approved=preflight.approve(evaluate().decisionKey(),approval());createPolicy(true);
        assertThatThrownBy(()->reuse(approved)).isInstanceOf(ConflictException.class);
    }
    @Test void noOpAndJsonFormattingDoNotInventAnInvalidation() {
        var p=createPolicy(true);var approved=preflight.approve(evaluate().decisionKey(),approval());
        policies.update(p.id(),new PolicyRequest(p.name(),p.policyType(),p.scopeType(),p.scopeValue(),"{  \"sensitivity\" : \"PUBLIC\" }",p.enforcementMode(),true,0L));
        assertThat(reuse(approved).approvalContextState()).isEqualTo("CURRENT");
    }
    @Test void rollbackToSameRuleDoesNotReviveAnOldApprovalVersion() {
        var p=createPolicy(true);var first=policies.versions(p.id()).versions().getFirst();
        var approved=preflight.approve(evaluate().decisionKey(),approval());policies.update(p.id(),policy("changed",true,0L));
        policies.rollback(p.id(),new RollbackRequest(1L,0L,first.snapshotDigest()));
        assertThatThrownBy(()->reuse(approved)).isInstanceOf(ConflictException.class);
        assertThat(preflight.get(approved.decisionKey()).approvalContextState()).isEqualTo("CHANGED");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void legacyDecisionsKeepUnknownContextAndCannotBeApprovedOrReused(boolean approved) {
        createPolicy(true);var d=evaluate();if(approved)d=preflight.approve(d.decisionKey(),approval());
        String key=d.decisionKey();db.update("update governance_preflight_decisions set policy_context_digest=null where decision_key=?",key);
        var before=stored(key);var current=preflight.get(key);assertThat(current.approvalContextState()).isEqualTo("UNRECORDED");
        if(approved)assertThatThrownBy(()->reuse(current)).isInstanceOf(ConflictException.class).hasMessageContaining("未记录");
        else assertThatThrownBy(()->preflight.approve(key,approval())).isInstanceOf(ConflictException.class).hasMessageContaining("未记录");
        assertThat(stored(key)).isEqualTo(before);
    }
    @Test void rejectingAStalePendingDecisionRemainsPossibleWithoutGrantingAccess() {
        var p=createPolicy(true);var d=evaluate();policies.update(p.id(),policy("changed",true,0L));
        var rejected=preflight.reject(d.decisionKey(),approval());assertThat(rejected.approvalStatus()).isEqualTo(GovernanceApprovalStatus.REJECTED);
        assertThat(rejected.requestAllowed()).isFalse();assertThat(rejected.approvalContextState()).isEqualTo("NOT_APPLICABLE");
        assertThatThrownBy(()->reuse(rejected)).hasMessageContaining("不是已批准");
    }
    @ParameterizedTest @ValueSource(strings={"enforcement","secret"})
    void enforcementSettingsArePartOfTheApprovalContext(String field) {
        createPolicy(true);var d=preflight.approve(evaluate().decisionKey(),approval());
        boolean enforcement=settings.isEnforcementEnabled(),secret=settings.isFailClosedOnSecret();
        try {
            if(field.equals("enforcement"))settings.setEnforcementEnabled(!enforcement);else settings.setFailClosedOnSecret(!secret);
            assertThatThrownBy(()->reuse(d)).isInstanceOf(ConflictException.class);
        } finally {settings.setEnforcementEnabled(enforcement);settings.setFailClosedOnSecret(secret);}
    }
    @Test void replayingOriginalPreflightNeverRefreshesAnApprovedContext() {
        var p=createPolicy(true);var d=preflight.approve(evaluate().decisionKey(),approval());var before=stored(d.decisionKey());
        policies.update(p.id(),policy("changed",true,0L));var read=preflight.evaluate(request(d.decisionKey()));
        assertThat(read.approvalStatus()).isEqualTo(GovernanceApprovalStatus.APPROVED);assertThat(read.approvalContextState()).isEqualTo("CHANGED");
        assertThat(stored(d.decisionKey())).isEqualTo(before);assertThatThrownBy(()->reuse(read)).isInstanceOf(ConflictException.class);
    }
    @Test void reuseSeesCommittedChangesEvenInsideAnOlderRepeatableReadCaller() {
        var p=createPolicy(true);var d=preflight.approve(evaluate().decisionKey(),approval());
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertThat(em.find(GovernancePolicy.class,p.id()).getRevision()).isZero();
            var other=new TransactionTemplate(manager);other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(ignored->policies.update(p.id(),policy("new committed",true,0L)));
            assertThatThrownBy(()->reuse(d)).isInstanceOf(ConflictException.class);
        });
    }
    @Test void concurrentApproveAndRejectCommitOnlyOneOutcomeAndOneAudit() throws Exception {
        createPolicy(true);var d=evaluate();var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.function.Function<Boolean,Boolean> operation=approve->{
                try {
                    new TransactionTemplate(manager).executeWithoutResult(tx->{
                        decisions.findByDecisionKey(d.decisionKey()).orElseThrow();
                        try {barrier.await(10,TimeUnit.SECONDS);}catch(Exception error){throw new IllegalStateException(error);}
                        if(approve)preflight.approve(d.decisionKey(),approval());else preflight.reject(d.decisionKey(),approval());
                    });return true;
                }catch(ObjectOptimisticLockingFailureException expected){return false;}
            };
            var a=pool.submit(()->operation.apply(true));var b=pool.submit(()->operation.apply(false));
            assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(db.queryForObject("select revision from governance_preflight_decisions where decision_key=?",Long.class,d.decisionKey())).isEqualTo(1L);
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='GOVERNANCE_APPROVAL'",Long.class,d.decisionKey())).isEqualTo(1L);
    }
}
