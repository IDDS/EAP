package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
class RuntimeWorkIntegrationTests {
    @Autowired RuntimeWorkService work;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    String endpoint() { return "substrate://reclaim/a-" + UUID.randomUUID(); }

    @Test void concurrentWorkRemainsAllowedButAnyActiveOrUnknownWorkPreventsReclaim() {
        String actor = endpoint(); AtomicInteger suspends = new AtomicInteger();
        try (var workflow = work.start(actor, "WORKFLOW"); var chat = work.start(actor, "CHAT")) {
            workflow.complete(true);
            assertThat(workflow.reclaim(suspends::incrementAndGet)).isEqualTo("SKIPPED_BUSY");
            chat.complete(false);
            var restarted = new RuntimeWorkService(db, manager);
            try (var experiment = restarted.start(actor, "EXPERIMENT")) {
                experiment.complete(true);
                assertThat(experiment.reclaim(suspends::incrementAndGet)).isEqualTo("SKIPPED_BUSY");
            }
            assertThat(suspends).hasValue(0);
        }
    }

    @Test void committedReclaimBarrierRejectsNewWorkUntilNativeAck() throws Exception {
        String actor = endpoint(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var ticket = work.start(actor, "WORKFLOW")) {
            ticket.complete(true);
            Future<String> result = executor.submit(() -> ticket.reclaim(() -> {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> new RuntimeWorkService(db, manager).start(actor, "CHAT")).isInstanceOf(BusinessException.class);
            release.countDown(); assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("CONFIRMED");
            try (var next = work.start(actor, "TEAM_EXPERIMENT")) { next.complete(true); }
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void ambiguousPauseSurvivesServiceReconstructionAndNeverAllowsAnotherSend() {
        String actor = endpoint();
        try (var ticket = work.start(actor, "WORKFLOW")) {
            ticket.complete(true);
            assertThat(ticket.reclaim(() -> { throw new IllegalStateException("synthetic timeout"); })).isEqualTo("UNCONFIRMED");
        }
        assertThatThrownBy(() -> new RuntimeWorkService(db, manager).start(actor, "CHAT")).isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("select reclaim_state from runtime_work_actors where actor_key=?", String.class, actor.substring(12))).isEqualTo("RECLAIM_UNKNOWN");
    }

    @Test void crashedReclaimerAndUnsettledDispatchHaveNoTtlBasedRelease() {
        String actor = endpoint();
        try (var unfinished = work.start(actor, "EXPERIMENT")) { /* Simulated lost business receipt. */ }
        // A deliberately old value within both H2 and MySQL TIMESTAMP ranges.
        db.update("update runtime_work_claims set updated_at=? where actor_key=?", java.sql.Timestamp.from(java.time.Instant.parse("2000-01-01T00:00:00Z")), actor.substring(12));
        try (var next = work.start(actor, "WORKFLOW")) {
            next.complete(true);
            assertThat(next.reclaim(() -> { throw new AssertionError("must not suspend"); })).isEqualTo("SKIPPED_BUSY");
        }
        db.update("update runtime_work_actors set reclaim_state='RECLAIMING',reclaim_owner=? where actor_key=?", UUID.randomUUID().toString(), actor.substring(12));
        assertThatThrownBy(() -> new RuntimeWorkService(db, manager).start(actor, "WORKFLOW")).isInstanceOf(BusinessException.class);
    }

    @Test void unknownOwnerCannotRequestReclaimAndCompletedClaimCannotBeRewritten() {
        String actor = endpoint();
        try (var ticket = work.start(actor, "WORKFLOW")) {
            ticket.complete(false);
            assertThat(ticket.reclaim(() -> { throw new AssertionError("must not suspend"); })).isEqualTo("SKIPPED_UNKNOWN");
            assertThatThrownBy(() -> ticket.complete(true)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void legacyGlobalUncertaintyBlocksReclaimButDoesNotReplayOrBlockNormalDispatch() {
        String legacy = UUID.randomUUID().toString();
        db.update("insert into runtime_work_claims values (?,'*','LEGACY','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", legacy);
        try (var ticket = work.start(endpoint(), "WORKFLOW")) {
            ticket.complete(true);
            assertThat(ticket.reclaim(() -> { throw new AssertionError("must not suspend"); })).isEqualTo("SKIPPED_BUSY");
        } finally { db.update("delete from runtime_work_claims where work_id=?", legacy); }
    }

    @Test void previewIsReadOnlyAndRequiredReclaimRejectsUnknownBeforeCreatingClaim() {
        String actor = endpoint();
        long actors = db.queryForObject("select count(*) from runtime_work_actors", Long.class);
        assertThat(work.reclaimReadiness(actor).allowed()).isTrue();
        assertThat(db.queryForObject("select count(*) from runtime_work_actors", Long.class)).isEqualTo(actors);
        try (var completed = work.start(actor, "CHAT")) { completed.complete(true); }
        assertThat(work.reclaimReadiness(actor).allowed()).isTrue();
        try (var unknown = work.start(actor, "EXPERIMENT")) { unknown.complete(false); }
        assertThat(work.reclaimReadiness(actor).code()).isEqualTo("ACTOR_UNRESOLVED_WORK");
        assertThatThrownBy(() -> work.start(actor, "WORKFLOW", true)).isInstanceOf(BusinessException.class);
        assertThat(db.queryForObject("select count(*) from runtime_work_claims where actor_key=?", Long.class, actor.substring(12))).isEqualTo(2);
        try (var normal = work.start(actor, "WORKFLOW")) { normal.complete(true); }
    }

    @Test void globalUnknownPreventsRequiredReclaimAdmissionForNewActorWithoutConsumingClaim() {
        String legacy = UUID.randomUUID().toString(), actor = endpoint();
        db.update("insert into runtime_work_claims values (?,'*','LEGACY','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", legacy);
        try {
            assertThat(work.reclaimReadiness(actor).code()).isEqualTo("GLOBAL_UNRESOLVED_WORK");
            assertThatThrownBy(() -> work.start(actor, "WORKFLOW", true)).isInstanceOf(BusinessException.class);
            assertThat(db.queryForObject("select count(*) from runtime_work_claims where actor_key=?", Long.class, actor.substring(12))).isZero();
            assertThat(db.queryForObject("select state from runtime_work_claims where work_id=?", String.class, legacy)).isEqualTo("UNKNOWN");
        } finally { db.update("delete from runtime_work_claims where work_id=?", legacy); }
    }
}
