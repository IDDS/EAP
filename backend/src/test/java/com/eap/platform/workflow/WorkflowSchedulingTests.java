package com.eap.platform.workflow;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.workflow.execution.enabled=true",
    "eap.workflow.execution.worker-enabled=false","eap.workflow.execution.token=synthetic-test-only",
    "eap.workflow.execution.allowed-hosts=127.0.0.1","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class WorkflowSchedulingTests {
    @Autowired WorkflowSchedulingService scheduler;
    @Autowired WorkflowExecutionRepository executions;
    @Autowired WorkflowExecutionProperties properties;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    @BeforeEach void setup(){
        tx=new TransactionTemplate(manager);
        db.update("delete from workflow_phase_observations");db.update("delete from workflow_schedule_entries");
        db.update("delete from workflow_schedule_groups");db.update("delete from workflow_executions");
        db.update("update workflow_schedule_lock set dispatch_sequence=0");properties.setAgingSeconds(60);
    }
    WorkflowExecution enqueue(String applicant,long queuedMs,boolean enroll){
        return tx.execute(ignored->{
            scheduler.lockEnrollment();var r=new WorkflowExecution();r.workflowId=1L;r.runKey="wf-test-"+UUID.randomUUID();
            r.status="QUEUED";r.objective="synthetic";r.requester=applicant.substring(0,Math.min(applicant.length(),128));r.snapshotJson="{}";r.recoveryVersion=1;
            r.createdAt=Instant.ofEpochMilli(queuedMs);executions.saveAndFlush(r);if(enroll)scheduler.enroll(r,applicant);return r;
        });
    }
    @Test void applicantBehindLargeBurstReceivesNextTurnWithoutCandidateTruncation(){
        long now=System.currentTimeMillis();List<WorkflowExecution> a=new ArrayList<>();
        for(int i=0;i<130;i++)a.add(enqueue("trusted-A",now+i,true));
        var b=enqueue("trusted-B",now+140,true);
        assertThat(scheduler.claim().id).isEqualTo(a.getFirst().id);
        assertThat(scheduler.claim().id).isEqualTo(b.id);
        assertThat(scheduler.claim().id).isEqualTo(a.get(1).id);
        var rebuilt=new WorkflowSchedulingService(db,executions,properties,manager);
        assertThat(rebuilt.claim().id).isEqualTo(a.get(2).id);
        assertThat(db.queryForObject("select dispatch_sequence from workflow_schedule_lock",Long.class)).isEqualTo(4);
    }
    @Test void agedOldestOverridesNewApplicantsAndRecentService(){
        long now=System.currentTimeMillis();var served=enqueue("A",now-10,true);assertThat(scheduler.claim().id).isEqualTo(served.id);
        var old=enqueue("A",now-65000,true);enqueue("new-B",now,true);
        assertThat(scheduler.claim().id).isEqualTo(old.id);
        assertThat(db.queryForObject("select selection_reason from workflow_schedule_entries where execution_id=?",String.class,old.id)).isEqualTo("WAIT_AGING");
    }
    @Test void concurrentClaimsHaveDistinctOwnersAndDurableSequence()throws Exception{
        long now=System.currentTimeMillis();for(int i=0;i<12;i++)enqueue("applicant-"+(i%3),now+i,true);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            List<Future<WorkflowExecution>> futures=new ArrayList<>();for(int i=0;i<12;i++)futures.add(pool.submit(scheduler::claim));
            Set<Long> ids=new HashSet<>();Set<String> owners=new HashSet<>();
            for(var f:futures){var r=f.get(20,TimeUnit.SECONDS);assertThat(r.ownerEpoch).isEqualTo(1);ids.add(r.id);owners.add(r.ownerId);}
            assertThat(ids).hasSize(12);assertThat(owners).hasSize(12);
            assertThat(db.queryForObject("select count(distinct dispatch_sequence) from workflow_schedule_entries",Long.class)).isEqualTo(12);
            assertThat(scheduler.claim()).isNull();
        }finally{pool.shutdownNow();}
    }
    @Test void historicalQueueKeepsFifoAndHasNoInventedProfile(){
        long now=System.currentTimeMillis();var old=enqueue("legacy",now,false);var fresh=enqueue("new",now-65000,true);
        var claimed=scheduler.claim();assertThat(claimed.id).isEqualTo(old.id);
        scheduler.begin(claimed,1,"TASK_WAIT");assertThat(db.queryForObject("select count(*) from workflow_phase_observations",Long.class)).isZero();
        assertThat(scheduler.report().runs()).noneMatch(r->r.runKey().equals(old.runKey));
        assertThat(scheduler.claim().id).isEqualTo(fresh.id);
    }
    @Test void ownershipLossCannotCompleteOldProfileAndCannotForgeZeroDuration(){
        enqueue("A",System.currentTimeMillis()-50,true);var r=scheduler.claim();scheduler.begin(r,1,"TASK_WAIT");
        db.update("update workflow_executions set status='EXECUTION_UNKNOWN',owner_epoch=owner_epoch+1,owner_id=null where id=?",r.id);
        assertThatThrownBy(()->scheduler.finish(r,1,"TASK_WAIT","SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        var phase=scheduler.report().runs().getFirst().phases().getFirst();assertThat(phase.state()).isEqualTo("UNCONFIRMED");assertThat(phase.durationMs()).isNull();
        assertThat(db.queryForObject("select state from workflow_phase_observations",String.class)).isEqualTo("RUNNING");
        assertThat(WorkflowSchedulingService.elapsed(1,2)).isNull();
    }
    @Test void fullTrustedPrincipalDoesNotCollapseWhenDisplayRequesterIsTruncated(){
        String prefix="a".repeat(128);long now=System.currentTimeMillis();enqueue(prefix+"-A",now,true);enqueue(prefix+"-A",now+1,true);var b=enqueue(prefix+"-B",now+2,true);
        scheduler.claim();assertThat(scheduler.claim().id).isEqualTo(b.id);
        assertThat(db.queryForObject("select count(*) from workflow_schedule_groups",Long.class)).isEqualTo(2);
    }
    @Test void changedOwnerWithinSameEpochCannotCompleteOrPresentOldPhaseAsCurrent(){
        enqueue("A",System.currentTimeMillis(),true);var r=scheduler.claim();scheduler.begin(r,1,"TASK_WAIT");
        db.update("update workflow_executions set owner_id=? where id=?",UUID.randomUUID().toString(),r.id);
        assertThatThrownBy(()->scheduler.finish(r,1,"TASK_WAIT","SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        var changed=executions.findById(r.id).orElseThrow();
        assertThatThrownBy(()->scheduler.finish(changed,1,"TASK_WAIT","SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        assertThat(scheduler.report().runs().getFirst().phases().getFirst().state()).isEqualTo("UNCONFIRMED");
    }
    @Test void expiredResponsibilityDoesNotReportAnOpenPhaseAsValid(){
        enqueue("A",System.currentTimeMillis(),true);var r=scheduler.claim();scheduler.begin(r,1,"TASK_WAIT");
        db.update("update workflow_executions set started_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1000)),r.id);
        assertThatThrownBy(()->scheduler.finish(r,1,"TASK_WAIT","SUCCEEDED")).isInstanceOf(IllegalStateException.class);
        assertThat(scheduler.report().runs().getFirst().phases().getFirst().state()).isEqualTo("UNCONFIRMED");
        assertThat(scheduler.report().runs().getFirst().phases().getFirst().durationMs()).isNull();
    }
    @Test void enrollmentAndClaimRollbackDoNotConsumeTurn(){
        long now=System.currentTimeMillis();assertThatThrownBy(()->tx.execute(ignored->{enqueue("rolled-back",now,true);throw new IllegalStateException("abort");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from workflow_schedule_entries",Long.class)).isZero();
        enqueue("A",now-20,true);var r=scheduler.claim();scheduler.begin(r,1,"PREPARATION");scheduler.finish(r,1,"PREPARATION","CONFIRMED");
        var report=scheduler.report();assertThat(report.waitSamples()).isEqualTo(1);assertThat(report.waitP50Ms()).isGreaterThanOrEqualTo(20);
        assertThat(report.runs().getFirst().phases().getFirst().durationMs()).isNotNull();
        assertThat(report.queued()).isZero();assertThat(report.legacyQueued()).isZero();
    }
}
