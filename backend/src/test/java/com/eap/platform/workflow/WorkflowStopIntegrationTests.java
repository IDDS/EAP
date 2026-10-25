package com.eap.platform.workflow;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.registry.*;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.enabled=true",
        "eap.workflow.execution.worker-enabled=false", "eap.orchestration.execution.worker-enabled=false",
        "eap.workflow.execution.allowed-hosts=127.0.0.1", "eap.workflow.execution.allow-insecure-http=true"})
@ActiveProfiles("test")
class WorkflowStopIntegrationTests {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("eap.workflow.execution.token", () -> UUID.randomUUID().toString());
        String mysql = System.getenv("EAP_STOP_TEST_MYSQL");
        String name = "stop_" + UUID.randomUUID().toString().replace("-", "");
        r.add("spring.datasource.url", () -> mysql == null
                ? "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                : mysql + name + "?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true&serverTimezone=UTC&connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username", () -> mysql == null ? "sa" : "root");
        r.add("spring.datasource.password", () -> "");
        r.add("spring.datasource.driver-class-name", () -> mysql == null ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
        r.add("spring.jpa.database-platform", () -> mysql == null ? "org.hibernate.dialect.H2Dialect" : "org.hibernate.dialect.MySQLDialect");
    }
    @Autowired WorkflowService workflows;
    @Autowired WorkflowExecutionService executions;
    @Autowired WorkflowExecutionRepository repository;
    @Autowired WorkflowSchedulingService scheduling;
    @Autowired WorkflowExecutionProperties properties;
    @Autowired WorkflowStopService stops;
    @Autowired AgentRepository agents;
    @Autowired AgentRegistryService registry;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.eap.platform.audit.AuditLogRepository audits;
    @MockitoBean WorkflowTaskClient client;
    @MockitoBean GenseeSafetyService safety;
    Long workflow;
    String runKey;
    final AtomicInteger calls = new AtomicInteger();
    @BeforeEach void prepare() {
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,
                new SafetyDtos.DecisionResponse(1L, 1L, "test", SafetyDecision.ALLOW, null, null, "test", null, null, null)));
        var a = new Agent(); a.setCode("STOP-" + UUID.randomUUID()); a.setName("stop contract");
        a.setOwnerDepartment("test"); a.setOwnerUser("test"); a.setCategory("test");
        a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); a.setRiskLevel(RiskLevel.LOW); Long agent = agents.save(a).getId();
        registry.registerRuntime(agent, new AgentRegistryDtos.RuntimeInstanceRequest("stop-" + UUID.randomUUID(), "http://127.0.0.1:9999/tasks", "EAP_HTTP_TASK_V1", false));
        workflow = workflows.create(new WorkflowDtos.WorkflowRequest("STOP-" + UUID.randomUUID(), "stop contract", "synthetic")).id();
        workflows.upsertNode(workflow, new WorkflowDtos.NodeRequest("start", "start", "START", null, null, 0, 0, "{}"));
        for (String n : List.of("one", "two")) workflows.upsertNode(workflow, new WorkflowDtos.NodeRequest(n, n, "AGENT", agent, null, 0, 0, "{}"));
        workflows.addEdge(workflow, new WorkflowDtos.EdgeRequest("start", "one", "always"));
        workflows.addEdge(workflow, new WorkflowDtos.EdgeRequest("one", "two", "always"));
        runKey = executions.submit(workflow, new WorkflowDtos.RunRequest("stop contract"), "operator").runKey();
        calls.set(0);
        doAnswer(inv -> {
            calls.incrementAndGet(); ((WorkflowTaskClient.Prepared) inv.getArgument(3)).beforeDispatch();
            return new WorkflowTaskClient.Receipt("SUCCEEDED", "synthetic receipt", 200, mapper.readTree("{\"result\":42}"));
        }).when(client).invoke(any(), anyMap(), any(), any());
    }
    @AfterEach void removeFromQueue() {
        db.update("update workflow_executions set status='EXECUTION_UNKNOWN' where workflow_id=? and status in ('QUEUED','RUNNING')", workflow);
    }
    WorkflowStopService.Report request(String key, String user) { return stops.request(workflow, runKey, new WorkflowStopService.Request(key), user); }
    WorkflowExecution row() { return repository.findByWorkflowIdAndRunKey(workflow, runKey).orElseThrow(); }
    @Test void queuedStopIsAtomicAuditedOnceAndNeverClaimed() {
        long before = audits.count(); var original = row();
        var report = request("stop-original", "operator");
        assertThat(report.state()).isEqualTo("APPLIED"); assertThat(report.effectsRolledBack()).isFalse();
        assertThat(report.appliedMs()).isNotNull(); assertThat(report.executionStatus()).isEqualTo("CANCELLED");
        assertThat(row().ownerEpoch).isEqualTo(original.ownerEpoch + 1); assertThat(row().ownerId).isNull();
        assertThat(request("stop-original", "operator")).isEqualTo(report);
        assertThat(audits.count()).isEqualTo(before + 1);
        assertThat(scheduling.claim()).isNull(); executions.processNext(); verifyNoInteractions(client);
        assertThat(row().stepsJson).isEqualTo(original.stepsJson); assertThat(row().snapshotJson).isEqualTo(original.snapshotJson);
    }
    @Test void cannotReplaceOriginalKeyOrPrincipalEvenWithSameDisplayPrefix() {
        String first = "u".repeat(128) + "first", second = "u".repeat(128) + "second";
        request("original", first);
        assertThatThrownBy(() -> request("different", first)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> request("original", second)).isInstanceOf(ConflictException.class);
        assertThat(request("original", first).requestedBy()).isEqualTo("u".repeat(128));
    }
    @Test void historicalAndUnknownExecutionsAreNotRewritten() {
        for (String status : List.of("QUEUED", "RUNNING", "EXECUTION_UNKNOWN", "SUCCEEDED")) {
            db.update("update workflow_executions set status=?,stop_version=0 where run_key=?", status, runKey);
            assertThat(stops.get(workflow, runKey).state()).isEqualTo("UNSUPPORTED");
            assertThatThrownBy(() -> request("stop", "operator")).isInstanceOf(ConflictException.class);
            assertThat(row().status).isEqualTo(status);
        }
        db.update("update workflow_executions set status='EXECUTION_UNKNOWN',stop_version=1 where run_key=?", runKey);
        assertThatThrownBy(() -> request("stop", "operator")).isInstanceOf(ConflictException.class);
        assertThat(row().status).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(db.queryForObject("select count(*) from workflow_stop_requests where execution_id=?", Long.class, row().id)).isZero();
    }
    @Test void executingStepKeepsReceiptAndNextStepNeverStarts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(inv -> {
            calls.incrementAndGet(); ((WorkflowTaskClient.Prepared) inv.getArgument(3)).beforeDispatch(); entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new WorkflowTaskClient.Receipt("SUCCEEDED", "original receipt", 200, mapper.readTree("{\"result\":42}"));
        }).when(client).invoke(any(), anyMap(), any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var worker = pool.submit(executions::processNext);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(request("stop-original", "operator").state()).isEqualTo("REQUESTED");
                assertThat(row().status).isEqualTo("RUNNING");
            } finally { release.countDown(); }
            worker.get(15, TimeUnit.SECONDS);
        }
        var result = executions.get(workflow, runKey);
        assertThat(result.status()).isEqualTo("CANCELLED"); assertThat(calls.get()).isEqualTo(1);
        assertThat(result.steps()).hasSize(2); assertThat(result.steps().get(1).status()).isEqualTo("SUCCEEDED");
        assertThat(executions.outputs(workflow, runKey).get("one").path("result").asInt()).isEqualTo(42);
        assertThat(stops.get(workflow, runKey).state()).isEqualTo("APPLIED");
        assertThat(scheduling.report().runs().stream().filter(r -> r.runKey().equals(runKey)).findFirst().orElseThrow().phases())
                .allMatch(p -> !Set.of("RUNNING", "UNCONFIRMED").contains(p.state()));
    }
    @Test void acceptedStopCannotTurnUnknownReceiptIntoCancelled() {
        doAnswer(inv -> {
            calls.incrementAndGet(); ((WorkflowTaskClient.Prepared) inv.getArgument(3)).beforeDispatch();
            request("stop", "operator"); return new WorkflowTaskClient.Receipt("EXECUTION_UNKNOWN", "lost receipt", null);
        }).when(client).invoke(any(), anyMap(), any(), any());
        executions.processNext();
        assertThat(row().status).isEqualTo("EXECUTION_UNKNOWN"); assertThat(calls.get()).isEqualTo(1);
        assertThat(stops.get(workflow, runKey).state()).isEqualTo("UNCONFIRMED");
        assertThat(request("stop", "operator").appliedMs()).isNull();
    }
    @Test void originalFailureRemainsFailureWhenStopIsPending() {
        doAnswer(inv -> {
            calls.incrementAndGet(); ((WorkflowTaskClient.Prepared) inv.getArgument(3)).beforeDispatch();
            request("stop", "operator"); return new WorkflowTaskClient.Receipt("FAILED", "original failure", 200);
        }).when(client).invoke(any(), anyMap(), any(), any());
        executions.processNext(); assertThat(row().status).isEqualTo("FAILED");
        assertThat(stops.get(workflow, runKey).state()).isEqualTo("FINISHED"); assertThat(calls.get()).isEqualTo(1);
    }
    @Test void durableRequestIsAppliedByFreshServiceWithoutReplayingBusiness() {
        var owner = scheduling.claim(); request("stop", "operator");
        var restarted = new WorkflowStopService(db, repository, scheduling, properties, audits, manager);
        assertThat(restarted.checkpoint(owner)).isFalse(); assertThat(stops.get(workflow, runKey).state()).isEqualTo("APPLIED");
        verifyNoInteractions(client);
    }
    @Test void expiredResponsibilityPreservesUnknownAndNeverAppliesStop() {
        var owner = scheduling.claim(); request("stop", "operator");
        db.update(connection -> {
            var statement = connection.prepareStatement("update workflow_executions set started_at=? where run_key=?");
            statement.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(properties.getTimeoutSeconds() + 5)),
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.setString(2, runKey);
            return statement;
        });
        assertThat(row().startedAt).isBefore(Instant.now().minusSeconds(properties.getTimeoutSeconds()));
        assertThat(stops.checkpoint(owner)).isFalse();
        assertThat(stops.get(workflow, runKey).state()).isEqualTo("UNCONFIRMED");
        assertThat(scheduling.claim()).isNull(); assertThat(row().status).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(request("stop", "operator").appliedMs()).isNull(); verifyNoInteractions(client);
    }
    @Test void staleOwnerCannotApplyPendingStopOrWriteReceiptAfterStop() {
        var old = scheduling.claim(); request("stop", "operator");
        String newer = UUID.randomUUID().toString();
        db.update("update workflow_executions set owner_epoch=owner_epoch+1,owner_id=? where run_key=?", newer, runKey);
        assertThat(stops.checkpoint(old)).isFalse(); assertThat(stops.get(workflow, runKey).state()).isEqualTo("REQUESTED");
        assertThat(stops.checkpoint(row())).isFalse(); assertThat(stops.get(workflow, runKey).state()).isEqualTo("APPLIED");
        var tx = new TransactionTemplate(manager);
        assertThat(tx.<Integer>execute(s -> repository.progressOwned(old.id, old.ownerEpoch, old.ownerId, "[]", "SUCCEEDED", "late", Instant.now()))).isZero();
        assertThat(row().status).isEqualTo("CANCELLED");
    }
    @Test void queueClaimAndStopRaceHaveOnlyTwoSafeOutcomes() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var claim = pool.submit(() -> { start.await(); return scheduling.claim(); });
            var stop = pool.submit(() -> { start.await(); return request("stop", "operator"); });
            start.countDown(); var owner = claim.get(10, TimeUnit.SECONDS); var report = stop.get(10, TimeUnit.SECONDS);
            if (owner == null) assertThat(report.state()).isEqualTo("APPLIED");
            else { assertThat(report.state()).isEqualTo("REQUESTED"); assertThat(stops.checkpoint(owner)).isFalse(); }
        }
        assertThat(row().status).isEqualTo("CANCELLED"); verifyNoInteractions(client);
    }
    @Test void concurrentIdenticalStopsWriteOneIntentAndOneAudit() throws Exception {
        long before = audits.count();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<WorkflowStopService.Report> task = () -> { start.await(); return request("original", "operator"); };
            var one = pool.submit(task); var two = pool.submit(task); start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(two.get(10, TimeUnit.SECONDS));
        }
        assertThat(audits.count()).isEqualTo(before + 1);
    }
    @Test void invalidKeysAndWrongWorkflowDoNotWriteIntent() {
        for (String key : Arrays.asList(null, "", "with space", "x".repeat(65)))
            assertThatThrownBy(() -> request(key, "operator")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> request("key", " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> stops.request(workflow + 100000, runKey, new WorkflowStopService.Request("key"), "operator"))
                .isInstanceOf(BusinessException.class);
        assertThat(stops.get(workflow, runKey).state()).isEqualTo("NONE");
    }
}
