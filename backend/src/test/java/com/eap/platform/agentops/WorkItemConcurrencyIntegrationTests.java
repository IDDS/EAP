package com.eap.platform.agentops;

import com.eap.platform.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "test.work-item-concurrency-isolation=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkItemConcurrencyIntegrationTests {
    @Autowired AgentWorkItemRepository items;
    @Autowired WorkItemService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate db;
    private Long id;

    @BeforeEach void fixture() {
        AgentWorkItem item = new AgentWorkItem();
        item.itemKey = "concurrency-" + UUID.randomUUID();
        item.itemType = WorkItemType.GENERAL;
        item.title = "Concurrent work item fixture";
        item.severity = WorkItemSeverity.P2;
        item.sourceType = "TEST";
        item.evidenceJson = "{\"sample\":\"original\"}";
        id = items.saveAndFlush(item).id;
    }

    @Test void concurrentClaimsKeepOneOwnerAndOneCommittedAuditTrail() throws Exception {
        var readers = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claim("alice", readers));
            var second = pool.submit(() -> claim("bob", readers));
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(o -> o.failure() == null)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o.failure() != null).findFirst().orElseThrow().failure())
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
            String winner = outcomes.stream().filter(o -> o.failure() == null).findFirst().orElseThrow().owner();
            var saved = items.findById(id).orElseThrow();
            assertThat(saved.status).isEqualTo(WorkItemStatus.CLAIMED);
            assertThat(saved.assignee).isEqualTo(winner);
            assertThat(db.queryForObject("select count(*) from agent_work_item_events where work_item_id=?", Long.class, id)).isEqualTo(1L);
            assertThat(db.queryForObject("select actor from agent_work_item_events where work_item_id=?", String.class, id)).isEqualTo(winner);
            assertThat(db.queryForObject("select count(*) from audit_logs where resource_type='AGENT_WORK_ITEM' and resource_id=?", Long.class, id.toString())).isEqualTo(1L);
        }
    }

    private record Claim(String owner, RuntimeException failure) {}
    private Claim claim(String owner, CyclicBarrier readers) {
        try {
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                // Both real transactions read OPEN before either attempts a transition.
                assertThat(items.findById(id).orElseThrow().status).isEqualTo(WorkItemStatus.OPEN);
                try { readers.await(10, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException("Concurrent reader barrier failed", ex); }
                service.transition(id, new AgentOpsDtos.WorkItemTransitionRequest(
                        WorkItemStatus.CLAIMED, owner, null, owner, null, null));
            });
            return new Claim(owner, null);
        } catch (RuntimeException ex) { return new Claim(owner, ex); }
    }

    @Test void staleSignalSnapshotCannotEraseACommittedClaim() throws Exception {
        var read = new CountDownLatch(1);
        var update = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                var snapshot = items.findById(id).orElseThrow();
                read.countDown();
                try { if (!update.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Update gate timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                // Signal synchronization saves evidence on this same entity; it must not restore stale owner/status fields.
                snapshot.lastDetectedAt = Instant.now();
                snapshot.evidenceJson = "{\"sample\":\"stale-signal\"}";
                items.saveAndFlush(snapshot);
            }));
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                service.transition(id, new AgentOpsDtos.WorkItemTransitionRequest(
                        WorkItemStatus.CLAIMED, "alice", null, "alice", null, null));
            } finally { update.countDown(); }
            assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ObjectOptimisticLockingFailureException.class);
            var saved = items.findById(id).orElseThrow();
            assertThat(saved.assignee).isEqualTo("alice");
            assertThat(saved.status).isEqualTo(WorkItemStatus.CLAIMED);
            assertThat(saved.evidenceJson).isEqualTo("{\"sample\":\"original\"}");
            assertThat(db.queryForObject("select count(*) from agent_work_item_events where work_item_id=?", Long.class, id)).isEqualTo(1L);
        }
    }

    @Test void concurrentUpdateResponseRequestsReloadWithoutPrivateDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/concurrent-work-item")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("数据已被修改，请重新加载后再编辑"));
    }

    protected String migrationJdbcUrl() {
        return "jdbc:h2:mem:work_item_version_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    @Test void versionMigrationPreservesExistingWorkItemsAndEvents() throws Exception {
        String url = migrationJdbcUrl();
        String user = url.startsWith("jdbc:h2:") ? "sa" : "root";
        org.flywaydb.core.Flyway.configure().dataSource(url, user, "")
                .locations("classpath:db/migration").target("89").load().migrate();
        try (var c = DriverManager.getConnection(url, user, ""); var q = c.createStatement()) {
            q.executeUpdate("insert into agent_work_items(item_key,item_type,title,severity,status,source_type,source_id,assignee,assigned_team,evidence_basis,evidence_json,occurrence_count,first_detected_at,last_detected_at,resolution,resolution_evidence_json,created_at,updated_at) "
                    + "values('legacy-item','GENERAL','Original item','P1','WAITING','TEST','original-source','original-owner','original-team','HUMAN_CONFIRMED','original-evidence',3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'original-note','original-reference',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into agent_work_item_events(work_item_id,event_type,actor,from_status,to_status,note,evidence_json,created_at) "
                    + "select id,'STATUS_CHANGED','original-owner','CLAIMED','WAITING','original-note','original-reference',CURRENT_TIMESTAMP from agent_work_items where item_key='legacy-item'");
        }
        var before = originalRows(url, user, "agent_work_items");
        var events = originalRows(url, user, "agent_work_item_events");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, user, "")
                .locations("classpath:db/migration").target("90").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(originalRows(url, user, "agent_work_items")).isEqualTo(before);
        assertThat(originalRows(url, user, "agent_work_item_events")).isEqualTo(events);
        try (var c = DriverManager.getConnection(url, user, ""); var q = c.createStatement();
             var row = q.executeQuery("select row_version from agent_work_items where item_key='legacy-item'")) {
            assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isZero(); assertThat(row.wasNull()).isFalse();
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private List<Map<String, String>> originalRows(String url, String user, String table) throws Exception {
        var rows = new ArrayList<Map<String, String>>();
        try (var c = DriverManager.getConnection(url, user, ""); var q = c.createStatement();
             var result = q.executeQuery("select * from " + table + " order by id")) {
            while (result.next()) {
                var row = new LinkedHashMap<String, String>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    String column = result.getMetaData().getColumnName(i).toLowerCase(java.util.Locale.ROOT);
                    if (!"row_version".equals(column)) row.put(column, result.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @RestController static class ConflictingController {
        @PostMapping("/concurrent-work-item") void update() {
            throw new ObjectOptimisticLockingFailureException("private-entity-detail", "private-record-detail");
        }
    }
}
