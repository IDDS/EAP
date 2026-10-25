package com.eap.platform.observability;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real Spring transactions and original metadata projection; no provider, Actor or tool execution. */
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class ObservationAlertIntegrationTests {
    @Autowired ObservationAlertService alerts;
    @Autowired ObservationCoverageService coverage;
    @Autowired JdbcTemplate db;
    @Autowired AgentRepository agents;
    @Autowired com.eap.platform.config.SecurityProperties security;
    long agent, runtime, operation;
    @BeforeEach void setup() {
        db.update("delete from observation_alert_audit"); db.update("delete from observation_alerts");
        db.update("delete from observation_alert_heads"); db.update("delete from observation_operations");
        var a = new Agent(); a.setCode("ALERT-"+UUID.randomUUID()); a.setName("Public metadata fixture");
        a.setOwnerDepartment("test"); a.setOwnerUser("test"); a.setCategory("test");
        a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); a.setRiskLevel(RiskLevel.LOW); agent=agents.save(a).getId();
        String key="eap-agents/alert-"+UUID.randomUUID();
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,last_heartbeat_at,created_at,updated_at,platform_actor_uid,platform_actor_template) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'original-uid','original-template')",agent,key,"substrate://"+key);
        runtime=db.queryForObject("select id from agent_runtime_instances where instance_key=?",Long.class,key);
        operation=enroll();
    }
    long enroll() {
        String key="public-alert-"+UUID.randomUUID(), trace=UUID.randomUUID().toString().replace("-", "");
        var r=db.queryForMap("select * from agent_runtime_instances where id=?",runtime);
        db.update("insert into observation_operations(entry_kind,operation_key,agent_id,trace_id,runtime_id,instance_key,endpoint,actor_uid,actor_template,registration_state,expected_agent_spans,expected_model_events,span_name,runtime_status,created_at) values('AX_ECHO',?,?,?,?,?,?,?,'original-template','CONFIRMED',1,0,'ax.task','SUCCEEDED',CURRENT_TIMESTAMP(6))",
                key,agent,trace,runtime,r.get("instance_key"),r.get("endpoint"),"original-uid");
        long id=db.queryForObject("select id from observation_operations where operation_key=?",Long.class,key);
        db.update("insert into observation_operation_events values(?,'DISPATCH_COMMITTED','ORIGINAL_DISPATCH_ONLY',null,CURRENT_TIMESTAMP(6))",id);
        return id;
    }
    long count(String table) {return db.queryForObject("select count(*) from "+table,Long.class);}
    ObservationAlertService.Alert only(ObservationAlertService.View v) {assertThat(v.alerts()).hasSize(1);return v.alerts().getFirst();}
    ObservationAlertService.Acknowledge request(ObservationAlertService.Alert a) {return new ObservationAlertService.Acknowledge(a.factRevision(),a.factDigest());}
    void span(long id,String span) {
        String trace=db.queryForObject("select trace_id from observation_operations where id=?",String.class,id);
        db.update("insert into trace_span_observations(trace_id,span_id,endpoint_key,agent_id,span_name,span_kind,duration_ms,observed_at,created_at) values(?,?,?,?,'ax.task','AGENT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",trace,span,"runtime-"+runtime,agent);
    }
    void status(String value) {db.update("update observation_operations set runtime_status=? where id=?",value,operation);}
    @Test void readIsSideEffectFreeAndDigestExcludesInspectionClock() {
        var one=alerts.inspect(operation);var two=alerts.inspect(operation);
        assertThat(one.observedFactDigest()).isEqualTo(two.observedFactDigest());
        assertThat(one.operation().coverage()).isEqualTo("MISSING_AGENT");
        assertThat(one.reconciliationCurrent()).isFalse();assertThat(one.reconciledRevision()).isNull();
        assertThat(count("observation_alert_heads")).isZero();assertThat(count("observation_alerts")).isZero();assertThat(count("observation_alert_audit")).isZero();
    }
    @Test void sameFactReconciliationPersistsOnceAcrossServiceInstances() {
        var first=alerts.reconcile(operation,"first-subject");var again=alerts.reconcile(operation,"second-subject");
        assertThat(again).isEqualTo(first);assertThat(first.reconciledRevision()).isEqualTo(1);
        assertThat(count("observation_alerts")).isEqualTo(1);assertThat(count("observation_alert_audit")).isEqualTo(1);
        assertThat(new ObservationAlertService(db,coverage).inspect(operation)).isEqualTo(first);
        assertThat(first.audit().getFirst().principalDigest()).matches("[a-f0-9]{64}").doesNotContain("subject");
    }
    @Test void concurrentOriginalReconciliationsHaveOneVersionAndOneAlert()throws Exception {
        var pool=Executors.newFixedThreadPool(4);var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<ObservationAlertService.View>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return alerts.reconcile(operation,"concurrent");}));
            start.countDown();var ids=new HashSet<Long>();for(var f:futures)ids.add(only(f.get(20,TimeUnit.SECONDS)).id());
            assertThat(ids).hasSize(1);assertThat(count("observation_alert_heads")).isEqualTo(1);
            assertThat(count("observation_alerts")).isEqualTo(1);assertThat(count("observation_alert_audit")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void acknowledgementIsIdempotentAndPreservesFirstAudit() {
        var a=only(alerts.reconcile(operation,"creator"));var first=alerts.acknowledge(operation,a.id(),request(a),"reader-one");
        assertThat(only(first).lifecycleState()).isEqualTo("ACKNOWLEDGED");
        assertThat(alerts.acknowledge(operation,a.id(),request(a),"reader-two")).isEqualTo(first);
        assertThat(count("observation_alert_audit")).isEqualTo(2);
        assertThat(db.queryForObject("select runtime_status from observation_operations where id=?",String.class,operation)).isEqualTo("SUCCEEDED");
    }
    @Test void uncoordinatedNewEvidenceRejectsOldAcknowledgementWithoutWrites() {
        var a=only(alerts.reconcile(operation,"creator"));span(operation,"s1");
        assertThat(alerts.inspect(operation).reconciliationCurrent()).isFalse();
        assertThatThrownBy(()->alerts.acknowledge(operation,a.id(),request(a),"reader")).isInstanceOf(ConflictException.class);
        assertThat(count("observation_alert_audit")).isEqualTo(1);
        assertThat(only(alerts.inspect(operation)).lifecycleState()).isEqualTo("ACTIVE");
    }
    @Test void evidenceRecoveryClosesOnlyMetadataAlertAndRetainsHistory() {
        var a=only(alerts.reconcile(operation,"creator"));span(operation,"s1");var recovered=alerts.reconcile(operation,"reconciler");
        assertThat(recovered.reconciledRevision()).isEqualTo(2);assertThat(only(recovered).id()).isEqualTo(a.id());
        assertThat(only(recovered).lifecycleState()).isEqualTo("RESOLVED");assertThat(recovered.operation().coverage()).isEqualTo("COMPLETE");
        assertThat(recovered.audit()).extracting(ObservationAlertService.Audit::event).containsExactlyInAnyOrder("CREATED","RESOLVED");
    }
    @Test void repeatedProjectedStateStillNeedsNewMonotonicRevision() {
        var original=only(alerts.reconcile(operation,"one"));alerts.acknowledge(operation,original.id(),request(original),"reader");
        status("FAILED");var second=alerts.reconcile(operation,"two");assertThat(second.reconciledRevision()).isEqualTo(2);
        status("SUCCEEDED");var third=alerts.reconcile(operation,"three");
        var current=third.alerts().getFirst();assertThat(current.factRevision()).isEqualTo(3);
        assertThat(current.factDigest()).isEqualTo(original.factDigest());
        assertThat(third.alerts()).filteredOn(a->a.lifecycleState().equals("SUPERSEDED")).hasSize(2);
        assertThatThrownBy(()->alerts.acknowledge(operation,current.id(),request(original),"reader")).isInstanceOf(ConflictException.class);
        assertThat(onlyCurrent(alerts.acknowledge(operation,current.id(),request(current),"reader")).lifecycleState()).isEqualTo("ACKNOWLEDGED");
    }
    ObservationAlertService.Alert onlyCurrent(ObservationAlertService.View v) {return v.alerts().stream().filter(a->a.factRevision()==v.reconciledRevision()).findFirst().orElseThrow();}
    @Test void sameProjectedGapOnDifferentOriginalOperationsCannotShareAcknowledgement() {
        var a=only(alerts.reconcile(operation,"creator"));long other=enroll();var b=only(alerts.reconcile(other,"creator"));
        assertThat(a.id()).isNotEqualTo(b.id());assertThat(a.factDigest()).isNotEqualTo(b.factDigest());
        assertThatThrownBy(()->alerts.acknowledge(other,a.id(),request(a),"reader")).isInstanceOf(BusinessException.class);
        assertThat(count("observation_alert_audit")).isEqualTo(2);
    }
    @Test void completeObservationDoesNotResolveUnknownExecutionOrChangeSpend() {
        span(operation,"s1");status("EXECUTION_UNKNOWN");long accounts=count("budget_accounts"),reservations=count("budget_reservations"),models=count("model_invocation_events");
        var a=only(alerts.reconcile(operation,"creator"));assertThat(a.kind()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(a.coverage()).isEqualTo("COMPLETE");alerts.acknowledge(operation,a.id(),request(a),"reader");
        assertThat(alerts.inspect(operation).operation().executionStatus()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(count("budget_accounts")).isEqualTo(accounts);assertThat(count("budget_reservations")).isEqualTo(reservations);assertThat(count("model_invocation_events")).isEqualTo(models);
        assertThat(count("observation_operation_events")).isEqualTo(1);
    }
    @Test void gapAndUnknownHaveSeparateAlertsAndAcknowledgements() {
        status("EXECUTION_UNKNOWN");var v=alerts.reconcile(operation,"creator");
        assertThat(v.alerts()).extracting(ObservationAlertService.Alert::kind).containsExactlyInAnyOrder("EVIDENCE_GAP","EXECUTION_UNKNOWN");
        var a=v.alerts().getFirst();var acknowledged=alerts.acknowledge(operation,a.id(),request(a),"reader");
        assertThat(acknowledged.alerts()).filteredOn(x->x.lifecycleState().equals("ACTIVE")).hasSize(1);
    }
    @Test void pendingAndFailedWithCompleteEvidenceAreNotGapOrUnknown() {
        status("RUNNING");assertThat(alerts.reconcile(operation,"creator").alerts()).isEmpty();
        span(operation,"s1");status("FAILED");assertThat(alerts.reconcile(operation,"creator").alerts()).isEmpty();
        assertThat(alerts.inspect(operation).operation().executionStatus()).isEqualTo("FAILED");
    }
    @Test void dispatchDeadlineChangesProjectedFactWithoutRewritingRunningStatus() {
        status("RUNNING");db.update("update observation_operations set dispatch_deadline=? where id=?",Timestamp.from(Instant.now().plusSeconds(60)),operation);
        var before=alerts.reconcile(operation,"creator");assertThat(before.alerts()).isEmpty();
        db.update("update observation_operations set dispatch_deadline=? where id=?",Timestamp.from(Instant.now().minusSeconds(60)),operation);
        var after=alerts.reconcile(operation,"reader");assertThat(after.observedFactDigest()).isNotEqualTo(before.observedFactDigest());
        assertThat(after.alerts()).extracting(ObservationAlertService.Alert::kind).contains("EXECUTION_UNKNOWN");
        assertThat(db.queryForObject("select runtime_status from observation_operations where id=?",String.class,operation)).isEqualTo("RUNNING");
    }
    @Test void identityReplacementCreatesNewFactAndCannotBorrowExistingConfirmation() {
        var a=only(alerts.reconcile(operation,"creator"));db.update("update agent_runtime_instances set platform_actor_uid='replacement-uid' where id=?",runtime);
        var v=alerts.reconcile(operation,"reader");assertThat(v.operation().coverage()).isEqualTo("IDENTITY_UNCONFIRMED");
        assertThat(v.observedFactDigest()).isNotEqualTo(a.factDigest());assertThat(v.operation().agentSpans()).isNull();
        assertThatThrownBy(()->alerts.acknowledge(operation,a.id(),request(a),"reader")).isInstanceOf(ConflictException.class);
        assertThat(db.queryForObject("select actor_uid from observation_operations where id=?",String.class,operation)).isEqualTo("original-uid");
    }
    @Test void invalidAuditPrincipalRollsBackAllNewMetadata() {
        assertThatThrownBy(()->alerts.reconcile(operation," ")).isInstanceOf(BusinessException.class);
        assertThat(count("observation_alert_heads")).isZero();assertThat(count("observation_alerts")).isZero();assertThat(count("observation_alert_audit")).isZero();
    }
    @Test void boundedHistoryExplicitlyReportsTruncation() {
        for(int i=0;i<102;i++){status(i%2==0?"SUCCEEDED":"FAILED");alerts.reconcile(operation,"creator");}
        var v=alerts.inspect(operation);assertThat(v.alerts()).hasSize(100);assertThat(v.alertsTruncated()).isTrue();
        assertThat(v.audit()).hasSize(200);assertThat(v.auditTruncated()).isTrue();assertThat(v.reconciledRevision()).isEqualTo(102);
    }
    @Test void deletionOfBusinessEnrollmentDoesNotDeleteAuditOrBlockItsDeletion() {
        alerts.reconcile(operation,"creator");db.update("delete from observation_operations where id=?",operation);
        assertThat(count("observation_alerts")).isEqualTo(1);assertThat(count("observation_alert_audit")).isEqualTo(1);
        assertThatThrownBy(()->alerts.inspect(operation)).isInstanceOf(BusinessException.class);
    }
    @Test void productionVisibilityDoesNotExposeOrReconcileDemoEnrollment() {
        var before=security.getMode();
        try {
            db.update("update agents set record_origin='DEMO' where id=?",agent);
            security.setMode(com.eap.platform.config.SecurityProperties.Mode.JWT);
            assertThatThrownBy(()->alerts.inspect(operation)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(()->alerts.reconcile(operation,"governance")).isInstanceOf(BusinessException.class);
            assertThat(count("observation_alert_heads")).isZero();assertThat(count("observation_alerts")).isZero();
        } finally {security.setMode(before);}
    }
}
