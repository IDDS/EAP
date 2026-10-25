package com.eap.platform.observability;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.config.SecurityProperties;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class ObservationAlertReconciliationIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired ObservationCoverageService coverage;
    @Autowired ObservationAlertService alerts;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AgentRepository agents;
    @Autowired SecurityProperties security;
    @Autowired ApplicationContext context;
    ObservationAlertIntegrationTests fixture;
    @BeforeEach void setup() {
        fixture=new ObservationAlertIntegrationTests();
        fixture.db=db;fixture.coverage=coverage;fixture.alerts=alerts;fixture.agents=agents;fixture.security=security;fixture.setup();
        db.update("update observation_alert_scan_cursor set sweep_number=0,upper_operation_id=0,last_operation_id=0,cursor_revision=0,processed_count=0,failure_count=0,last_failure_operation_id=null,last_failure_code=null,last_failure_at=null where id=1");
    }
    ObservationAlertReconciliationService scan(int batch) {return scan(batch,10000,alerts);}
    ObservationAlertReconciliationService scan(int batch,long ms,ObservationAlertService service) {
        return new ObservationAlertReconciliationService(db,coverage,service,transactions,batch,ms);
    }
    Map<String,Object> cursor() {return db.queryForMap("select * from observation_alert_scan_cursor where id=1");}
    long field(String name) {return ((Number)cursor().get(name)).longValue();}
    @Test void featureIsDisabledByDefaultAndMigrationDoesNotEnrollAnyAlerts() {
        assertThat(context.getBeansOfType(ObservationAlertReconciliationWorker.class)).isEmpty();
        assertThat(field("sweep_number")).isZero();assertThat(fixture.count("observation_alert_heads")).isZero();
    }
    @Test void boundedBatchResumesFromDurableCursorInAnotherInstance() {
        long second=fixture.enroll(),third=fixture.enroll();
        assertThat(scan(1).runBatch().processed()).isEqualTo(1);
        assertThat(field("last_operation_id")).isEqualTo(fixture.operation);
        assertThat(scan(1).runBatch().processed()).isEqualTo(1);assertThat(field("last_operation_id")).isEqualTo(second);
        assertThat(scan(1).runBatch().sweepComplete()).isTrue();assertThat(field("last_operation_id")).isEqualTo(third);
        assertThat(fixture.count("observation_alert_heads")).isEqualTo(3);assertThat(field("processed_count")).isEqualTo(3);
    }
    @Test void newOperationsWaitUntilNextSweepAndDoNotMoveFrozenUpperBound() {
        long second=fixture.enroll();var one=scan(1).runBatch();long third=fixture.enroll();
        assertThat(field("upper_operation_id")).isEqualTo(second);
        assertThat(scan(10).runBatch().sweepComplete()).isTrue();assertThat(alerts.inspect(third).reconciledRevision()).isNull();
        var next=scan(10).runBatch();assertThat(next.sweep()).isEqualTo(one.sweep()+1);assertThat(next.processed()).isEqualTo(3);
        assertThat(alerts.inspect(third).reconciliationCurrent()).isTrue();
    }
    @Test void repeatedSweepsPreserveAcknowledgementDigestRevisionAndOriginalAuditTimestamps() {
        var a=fixture.only(alerts.reconcile(fixture.operation,"reader"));
        var before=alerts.acknowledge(fixture.operation,a.id(),fixture.request(a),"reader");
        var head=db.queryForMap("select * from observation_alert_heads where operation_id=?",fixture.operation);
        scan(10).runBatch();scan(10).runBatch();
        assertThat(alerts.inspect(fixture.operation)).isEqualTo(before);
        assertThat(db.queryForMap("select * from observation_alert_heads where operation_id=?",fixture.operation)).isEqualTo(head);
    }
    @Test void oldOperationsAreRevisitedForLateEvidenceWithoutAgeCutoff() {
        db.update("update observation_operations set created_at=? where id=?",Timestamp.from(Instant.now().minusSeconds(86400L*30)),fixture.operation);
        scan(10).runBatch();fixture.span(fixture.operation,"late-agent-span");scan(10).runBatch();
        var view=alerts.inspect(fixture.operation);assertThat(view.reconciledRevision()).isEqualTo(2);
        assertThat(view.operation().coverage()).isEqualTo("COMPLETE");assertThat(fixture.only(view).lifecycleState()).isEqualTo("RESOLVED");
    }
    @Test void sweepSeesExpiredDeadlineButDoesNotSettleUnknownOrRewriteOriginalRunning() {
        fixture.status("RUNNING");db.update("update observation_operations set dispatch_deadline=? where id=?",Timestamp.from(Instant.now().plusSeconds(600)),fixture.operation);
        scan(10).runBatch();assertThat(alerts.inspect(fixture.operation).alerts()).isEmpty();
        db.update("update observation_operations set dispatch_deadline=? where id=?",Timestamp.from(Instant.now().minusSeconds(60)),fixture.operation);
        var original=db.queryForMap("select * from observation_operations where id=?",fixture.operation);
        var accounts=db.queryForList("select * from budget_accounts order by id");
        scan(10).runBatch();assertThat(alerts.inspect(fixture.operation).alerts()).extracting(ObservationAlertService.Alert::kind).contains("EXECUTION_UNKNOWN");
        assertThat(db.queryForMap("select * from observation_operations where id=?",fixture.operation)).isEqualTo(original);
        assertThat(db.queryForList("select * from budget_accounts order by id")).isEqualTo(accounts);
    }
    @Test void laterIdentityDriftCreatesNewFactInsteadOfBorrowingAcknowledgement() {
        scan(10).runBatch();var a=fixture.only(alerts.inspect(fixture.operation));alerts.acknowledge(fixture.operation,a.id(),fixture.request(a),"reader");
        db.update("update agent_runtime_instances set platform_actor_uid='replacement-uid' where id=?",fixture.runtime);
        scan(10).runBatch();var v=alerts.inspect(fixture.operation);
        assertThat(v.reconciledRevision()).isEqualTo(2);assertThat(v.operation().coverage()).isEqualTo("IDENTITY_UNCONFIRMED");
        assertThat(v.alerts()).filteredOn(x->x.factRevision()==2).allMatch(x->x.lifecycleState().equals("ACTIVE"));
    }
    @Test void poisonOperationRollsBackPartialAuditAdvancesCursorAndDoesNotBlockHealthyOriginal() {
        long healthy=fixture.enroll();
        var failing=new ObservationAlertService(db,coverage) {
            @Override public View reconcile(long op,String principal) {
                var result=alerts.reconcile(op,principal);
                if(op==fixture.operation)throw new IllegalStateException("private caller content must never be retained");
                return result;
            }
        };
        var result=scan(10,10000,failing).runBatch();assertThat(result.failures()).isEqualTo(1);assertThat(result.processed()).isEqualTo(1);
        assertThat(alerts.inspect(fixture.operation).alerts()).isEmpty();assertThat(fixture.count("observation_alert_audit")).isEqualTo(1);
        assertThat(alerts.inspect(healthy).reconciliationCurrent()).isTrue();assertThat(field("last_failure_operation_id")).isEqualTo(fixture.operation);
        assertThat(cursor().get("last_failure_code")).isEqualTo("RECONCILIATION_FAILED");assertThat(cursor().toString()).doesNotContain("private caller");
        scan(10).runBatch();assertThat(alerts.inspect(fixture.operation).reconciliationCurrent()).isTrue();assertThat(field("failure_count")).isEqualTo(1);
    }
    @Test void concurrentReplicasSerializePerOperationAndNeverCreateDuplicateFacts()throws Exception {
        for(int i=0;i<7;i++)fixture.enroll();var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<ObservationAlertReconciliationService.Batch>>();
            for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await();return scan(2).runBatch();}));
            gate.countDown();for(var f:futures)assertThat(f.get(30,TimeUnit.SECONDS).failures()).isZero();
            scan(100).runBatch();
            assertThat(fixture.count("observation_alert_heads")).isEqualTo(8);assertThat(fixture.count("observation_alerts")).isEqualTo(8);
            assertThat(fixture.count("observation_alert_audit")).isEqualTo(8);
            assertThat(db.queryForObject("select max(fact_revision) from observation_alert_heads",Long.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void deletionAndProductionDemoFilteringDoNotBlockOtherOriginals() {
        long second=fixture.enroll();scan(1).runBatch();db.update("delete from observation_operations where id=?",second);
        assertThat(scan(10).runBatch().sweepComplete()).isTrue();assertThat(fixture.count("observation_alert_heads")).isEqualTo(1);
        var mode=security.getMode();
        try {
            db.update("update agents set record_origin='DEMO' where id=?",fixture.agent);security.setMode(SecurityProperties.Mode.JWT);
            assertThat(scan(10).runBatch().processed()).isZero();
            assertThat(fixture.count("observation_alert_heads")).isEqualTo(1);
        } finally {security.setMode(mode);}
    }
    @Test void emptyDatabaseHasNoUnboundedCursorWrites() {
        db.update("delete from observation_operations");var before=cursor();
        assertThat(scan(10).runBatch().sweepComplete()).isTrue();assertThat(scan(10).runBatch().processed()).isZero();assertThat(cursor()).isEqualTo(before);
    }
    @Test void elapsedTimeBudgetStopsBeforeAnotherOperation() {
        fixture.enroll();var slow=new ObservationAlertService(db,coverage) {
            @Override public View reconcile(long op,String principal) {
                var result=alerts.reconcile(op,principal);
                try {Thread.sleep(150);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted");}
                return result;
            }
        };
        var result=scan(50,100,slow).runBatch();assertThat(result.processed()).isLessThanOrEqualTo(1);assertThat(result.sweepComplete()).isFalse();
        assertThat(field("last_operation_id")).isLessThan(field("upper_operation_id"));
    }
    @Test void invalidBoundsFailBeforeAnyMetadataWrites() {
        var before=cursor();assertThatThrownBy(()->scan(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->scan(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->scan(1,99,alerts)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->scan(1,30001,alerts)).isInstanceOf(IllegalArgumentException.class);assertThat(cursor()).isEqualTo(before);
    }
}
