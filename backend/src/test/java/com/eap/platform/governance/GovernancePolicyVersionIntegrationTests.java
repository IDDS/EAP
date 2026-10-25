package com.eap.platform.governance;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.eap.platform.governance.GovernancePolicyDtos.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @Transactional
class GovernancePolicyVersionIntegrationTests {
    @Autowired GovernancePolicyService policies;
    @Autowired GovernancePreflightService preflight;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;

    PolicyRequest request(String name, boolean enabled, Long expected) {
        return new PolicyRequest(name, PolicyType.PRIVACY, "MODEL", "public-version-fixture",
                "{\"sensitivities\":[\"PUBLIC\"],\"action\":\"BLOCK\"}", EnforcementMode.BLOCK, enabled, expected);
    }
    PolicyResponse create() { return policies.create(request("PUBLIC-"+UUID.randomUUID(), false, null)); }
    List<Map<String,Object>> snapshots(long id) {
        return db.queryForList("select * from governance_policy_versions where policy_id=? order by revision", id);
    }
    Map<String,Object> storedPolicy(long id) {
        var row=db.queryForMap("select * from governance_policies where id=?",id);
        row.replaceAll((key,value)->value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value);
        return row;
    }
    void cleanup(long id) {
        db.update("delete from governance_policy_versions where policy_id=?", id);
        db.update("delete from governance_policies where id=?", id);
    }
    String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void createRecordsExactSnapshotAndTrustedPrincipalWithoutStoringPrincipalText() throws Exception {
        var original=SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("PUBLIC-version-owner", null, List.of()));
            var p=create();var history=policies.versions(p.id());var v=history.versions().getFirst();
            assertThat(p.revision()).isZero();assertThat(history.legacyHistoryUnavailable()).isFalse();
            assertThat(v.changeKind()).isEqualTo("CREATED");assertThat(v.restoredFromRevision()).isNull();
            assertThat(v.values().name()).isEqualTo(p.name());assertThat(v.values().enabled()).isFalse();
            assertThat(v.principalDigest()).isEqualTo(hash("PUBLIC-version-owner"));
            assertThat(v.snapshotDigest()).isEqualTo(hash(json.writeValueAsString(v.values())));
            assertThat(v.recordedAt()).isNotNull();assertThat(snapshots(p.id()).toString()).doesNotContain("PUBLIC-version-owner");
        } finally {SecurityContextHolder.getContext().setAuthentication(original);}
    }
    @Test void editingAndEnableDisableAppendVersionsWithoutChangingEarlierSnapshots() {
        var p=create();var first=snapshots(p.id());
        var one=policies.update(p.id(),request("changed", true, 0L));var prior=snapshots(p.id());
        var two=policies.update(p.id(),request("changed", false, 1L));
        assertThat(one.revision()).isEqualTo(1);assertThat(two.revision()).isEqualTo(2);
        assertThat(snapshots(p.id()).subList(0,1)).isEqualTo(first);
        assertThat(snapshots(p.id()).subList(0,2)).isEqualTo(prior);
        assertThat(policies.versions(p.id()).versions()).extracting(PolicyVersion::revision).containsExactly(2L,1L,0L);
    }
    @Test void identicalUpdateDoesNotInventANewVersionOrTouchItsTimestamp() {
        var p=create();var before=snapshots(p.id());var current=policies.update(p.id(),request(p.name(),false,0L));
        assertThat(current).isEqualTo(p);assertThat(snapshots(p.id())).isEqualTo(before);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void databaseJsonFormattingCannotTurnANoOpIntoAnUnrecordedVersionUpdate() {
        var p=create();
        try {
            var before=storedPolicy(p.id());var history=snapshots(p.id());
            var same=new PolicyRequest(p.name(),p.policyType(),p.scopeType(),p.scopeValue(),
                    "{ \"action\": \"BLOCK\", \"sensitivities\": [\"PUBLIC\"] }",p.enforcementMode(),false,0L);
            assertThat(policies.update(p.id(),same).revision()).isZero();
            assertThat(storedPolicy(p.id())).isEqualTo(before);
            assertThat(snapshots(p.id())).isEqualTo(history);
        } finally {cleanup(p.id());}
    }
    @Test void legacyHistoryReadDoesNotBackfillAndFirstActualEditCapturesTheBaseline() {
        String name="PUBLIC-legacy-"+UUID.randomUUID();
        db.update("insert into governance_policies(name,policy_type,scope_type,scope_value,rule_json,enforcement_mode,enabled,created_at,updated_at) values(?,'PRIVACY','MODEL','public-version-fixture','{}','WARN',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",name);
        long id=db.queryForObject("select id from governance_policies where name=?",Long.class,name);
        assertThat(policies.versions(id).versions()).isEmpty();assertThat(policies.versions(id).legacyHistoryUnavailable()).isTrue();
        assertThat(snapshots(id)).isEmpty();policies.update(id,request("new",false,0L));
        var history=policies.versions(id);assertThat(history.legacyHistoryUnavailable()).isTrue();
        assertThat(history.versions()).extracting(PolicyVersion::changeKind).containsExactly("UPDATED","BASELINE");
        assertThat(history.versions().get(1).values().name()).isEqualTo(name);
        assertThat(history.versions().get(1).values().ruleJson()).isEqualTo("{}");
    }
    @Test void missingNegativeOrStaleExpectedRevisionCannotOverwriteCurrentRules() {
        var p=create();policies.update(p.id(),request("current",false,0L));var before=snapshots(p.id());
        for(Long revision:Arrays.asList(null,-1L))assertThatThrownBy(()->policies.update(p.id(),request("other",true,revision))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->policies.update(p.id(),request("other",true,0L))).isInstanceOf(ConflictException.class);
        assertThat(snapshots(p.id())).isEqualTo(before);
        assertThat(db.queryForObject("select name from governance_policies where id=?",String.class,p.id())).isEqualTo("current");
    }
    @Test void rollbackPublishesAnExactOlderPayloadAsANewVersionAndRetainsAllOldRows() {
        var p=create();var source=policies.versions(p.id()).versions().getFirst();
        policies.update(p.id(),request("changed",true,0L));var before=snapshots(p.id());
        var restored=policies.rollback(p.id(),new RollbackRequest(1L,0L,source.snapshotDigest()));
        assertThat(restored.revision()).isEqualTo(2);assertThat(restored.name()).isEqualTo(p.name());assertThat(restored.enabled()).isFalse();
        var latest=policies.versions(p.id()).versions().getFirst();assertThat(latest.changeKind()).isEqualTo("ROLLED_BACK");
        assertThat(latest.restoredFromRevision()).isEqualTo(0L);assertThat(latest.snapshotDigest()).isEqualTo(source.snapshotDigest());
        assertThat(latest.values()).isEqualTo(source.values());assertThat(snapshots(p.id()).subList(0,2)).isEqualTo(before);
    }
    @Test void rollbackRejectsWrongDigestFutureRevisionAndMissingHistoryWithoutWriting() {
        var p=create();policies.update(p.id(),request("changed",true,0L));var before=snapshots(p.id());
        assertThatThrownBy(()->policies.rollback(p.id(),new RollbackRequest(1L,0L,"0".repeat(64)))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->policies.rollback(p.id(),new RollbackRequest(1L,1L,"0".repeat(64)))).isInstanceOf(BusinessException.class);
        assertThat(snapshots(p.id())).isEqualTo(before);
        db.update("delete from governance_policy_versions where policy_id=? and revision=0",p.id());
        assertThatThrownBy(()->policies.rollback(p.id(),new RollbackRequest(1L,0L,"0".repeat(64)))).isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("select revision from governance_policies where id=?",Long.class,p.id())).isEqualTo(1L);
    }
    @Test void rollbackWithIdenticalCurrentPayloadIsRejectedWithoutAFakeRevision() {
        var p=create();var source=policies.versions(p.id()).versions().getFirst();
        policies.update(p.id(),request("other",true,0L));policies.update(p.id(),request(p.name(),false,1L));
        var before=snapshots(p.id());assertThatThrownBy(()->policies.rollback(p.id(),new RollbackRequest(2L,0L,source.snapshotDigest()))).isInstanceOf(BusinessException.class);
        assertThat(snapshots(p.id())).isEqualTo(before);
    }
    @Test void latestFiftyHistoryEntriesReportTruncationAndKeepOlderStoredVersions() {
        var p=create();for(long i=0;i<51;i++)policies.update(p.id(),request("version-"+i,false,i));
        var history=policies.versions(p.id());assertThat(history.truncated()).isTrue();assertThat(history.versions()).hasSize(50);
        assertThat(history.versions().getFirst().revision()).isEqualTo(51);assertThat(history.versions().getLast().revision()).isEqualTo(2);
        assertThat(history.legacyHistoryUnavailable()).isFalse();assertThat(snapshots(p.id())).hasSize(52);
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedHistoryInsertRollsBackTheAlreadyFlushedPolicyUpdate() {
        var p=create();
        try {
            db.update("insert into governance_policy_versions select policy_id,1,change_kind,restored_from_revision,snapshot_json,snapshot_digest,principal_digest,recorded_at from governance_policy_versions where policy_id=? and revision=0",p.id());
            var before=storedPolicy(p.id());var history=snapshots(p.id());
            assertThatThrownBy(()->policies.update(p.id(),request("must-rollback",true,0L))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(storedPolicy(p.id())).isEqualTo(before);
            assertThat(snapshots(p.id())).isEqualTo(history);
        } finally {cleanup(p.id());}
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentTransactionsWithTheSameReadVersionPublishExactlyOneSnapshot() throws Exception {
        var p=create();var before=snapshots(p.id());var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> writer=()->{
                try {
                    new TransactionTemplate(manager).executeWithoutResult(tx->{
                        assertThat(em.find(GovernancePolicy.class,p.id()).getRevision()).isZero();
                        try {barrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
                        policies.update(p.id(),request(Thread.currentThread().getName(),true,0L));
                    });return true;
                } catch(ObjectOptimisticLockingFailureException|ConflictException expected){return false;}
            };
            var a=pool.submit(writer);var b=pool.submit(writer);
            assertThat(List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(snapshots(p.id())).hasSize(2);assertThat(snapshots(p.id()).subList(0,1)).isEqualTo(before);
            assertThat(policies.versions(p.id()).currentRevision()).isEqualTo(1);
        } finally {cleanup(p.id());}
    }
    @Test void newPreflightFreezesMatchedRevisionWhileOriginalDecisionReplayKeepsItsResult() {
        var p=policies.create(request("preflight",true,null));String key="PUBLIC-decision-"+UUID.randomUUID();
        var first=requestForDecision(key);var original=preflight.evaluate(first);
        assertThat(original.matchedPolicies()).filteredOn(row->row.id().equals(p.id())).singleElement().satisfies(row->assertThat(row.revision()).isZero());
        policies.update(p.id(),request("preflight-next",true,0L));
        assertThat(preflight.evaluate(first)).isEqualTo(original);
        var next=preflight.evaluate(requestForDecision("PUBLIC-decision-"+UUID.randomUUID()));
        assertThat(next.matchedPolicies()).filteredOn(row->row.id().equals(p.id())).singleElement().satisfies(row->assertThat(row.revision()).isEqualTo(1L));
    }
    GovernanceDataDtos.PreflightRequest requestForDecision(String key) {
        Long agentId=db.queryForObject("select min(id) from agents",Long.class);assertThat(agentId).isNotNull();
        return new GovernanceDataDtos.PreflightRequest(key,"event-"+key,agentId,null,null,"a".repeat(32),
                GovernanceDataDirection.INPUT_TO_MODEL,"PUBLIC_FIXTURE",GovernanceDataSensitivity.PUBLIC,false,false,0,0,
                "public-fixture","PUBLIC","public-version-fixture",List.of(),null,Instant.now());
    }
    @Test void historicalMatchedPolicyWithoutRevisionRemainsUnknownRatherThanBecomingZero() throws Exception {
        var legacy=json.readValue("{\"id\":1,\"name\":\"legacy\",\"policyType\":\"PRIVACY\",\"enforcementMode\":\"BLOCK\",\"reasonCode\":\"fixture\"}",GovernanceDataDtos.MatchedPolicy.class);
        assertThat(legacy.revision()).isNull();
    }
}
