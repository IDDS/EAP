package com.eap.platform.workflow;

import com.eap.platform.agent.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.gateway.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Reproduces JPA Instant writes and raw JDBC reads with the deployed +08 session. */
@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.budget.enabled=true",
        "eap.workflow.execution.worker-enabled=false", "eap.orchestration.execution.worker-enabled=false",
        "eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class WorkflowBudgetTimezoneMySqlIT {
    @Autowired JdbcTemplate db;
    @Autowired WorkflowExecutionRepository rows;
    @Autowired WorkflowService workflows;
    @Autowired WorkflowSchedulingService scheduling;
    @Autowired AgentService agents;
    @Autowired BudgetLedger budget;
    @Autowired ObjectMapper json;
    long agent;

    @DynamicPropertySource static void isolatedMySql(DynamicPropertyRegistry p) {
        String url = System.getenv("EAP_ISOLATED_MYSQL_URL");
        if (url == null || !url.startsWith("jdbc:mysql://127.0.0.1:3306/eap_holdout_fixture?")
                || !url.contains("connectionTimeZone=Asia/Shanghai")
                || !url.contains("forceConnectionTimeZoneToSession=true"))
            throw new IllegalStateException("Isolated loopback +08 MySQL required");
        p.add("spring.datasource.url", () -> url);
        p.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        p.add("spring.datasource.username", () -> "root");
        p.add("spring.datasource.password", () -> "");
        p.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    }

    @BeforeEach void prepare() {
        var a = agents.create(new AgentDtos.AgentRequest("TZ-" + UUID.randomUUID(), "Timezone fixture", "",
                "Test", "Test", "Test", RiskLevel.LOW));
        agents.updateStatus(a.id(), AgentLifecycleStatus.ACTIVE); agent = a.id();
        assertThat(db.queryForObject("select TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),CURRENT_TIMESTAMP())", Long.class)).isEqualTo(28800);
    }
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    WorkflowExecution running(Instant started) {
        String key = "tz-" + UUID.randomUUID();
        long workflow = workflows.create(new WorkflowDtos.WorkflowRequest(key, "Timezone fixture", "")).id();
        WorkflowExecution row = new WorkflowExecution();
        row.workflowId = workflow; row.runKey = key; row.status = "RUNNING";
        row.objective = "Synthetic arithmetic"; row.requester = "test"; row.snapshotJson = "{}";
        row.stepsJson = "[{\"sequence\":0,\"taskKey\":\"" + key + ":0\",\"agentId\":" + agent
                + ",\"runtimeInstanceKey\":\"synthetic-instance\",\"status\":\"DISPATCHING\"}]";
        row.createdAt = started; row.startedAt = started; row.recoveryVersion = 1;
        row.ownerEpoch = 1; row.ownerId = UUID.randomUUID().toString();
        row = rows.saveAndFlush(row);
        scheduling.enroll(row, "timezone-test");
        db.update("insert into workflow_phase_observations values (?,?,?,0,'TASK_WAIT','RUNNING',?,null)",
                row.id, row.ownerEpoch, row.ownerId, started.toEpochMilli());
        var request = new MockHttpServletRequest(); request.addHeader("X-EAP-Task-Key", key + ":0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return row;
    }
    BudgetLedger.Ticket admit(WorkflowExecution row) {
        var request = json.createObjectNode().put("model", "synthetic").put("max_tokens", 4);
        request.set("messages", json.createArrayNode().add(json.createObjectNode().put("role", "user").put("content", "1+2")));
        return budget.admitModel(request, agent, null, null, "synthetic-trace", row.runKey);
    }

    @Test void currentJpaWorkflowAdmitsDespiteNonUtcJdbcSession() {
        var row = running(Instant.now());
        var plain = db.queryForObject("select started_at from workflow_executions where id=?", java.sql.Timestamp.class, row.id);
        assertThat(plain.toInstant()).isBefore(Instant.now().minusSeconds(7 * 3600));
        assertThat(rows.findById(row.id).orElseThrow().startedAt).isAfter(Instant.now().minusSeconds(30));
        var ticket = admit(row);
        assertThat(ticket.id()).isPositive();
        assertThatThrownBy(() -> admit(row)).isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException)e).getCode()).isEqualTo("budget_invocation_recorded"));
        assertThat(db.queryForObject("select count(*) from budget_reservations where agent_id=?", Long.class, agent)).isEqualTo(1);
    }
    @Test void expiredJpaWorkflowIsKnownRejectionBeforeBudgetReservation() {
        var row = running(Instant.now().minusSeconds(901));
        assertThatThrownBy(() -> admit(row)).isInstanceOf(GatewayException.class).satisfies(e -> {
            var failure = (GatewayException)e;
            assertThat(failure.getCode()).isEqualTo("workflow_not_running");
            assertThat(failure.admissionOutcome()).isEqualTo("REJECTED");
        });
        assertThat(db.queryForObject("select count(*) from budget_reservations where agent_id=?", Long.class, agent)).isZero();
    }
    @Test void currentJpaOwnerStillHasRunningPhaseInSchedulingReport() {
        var row = running(Instant.now());
        var report = scheduling.report().runs().stream().filter(r -> r.runKey().equals(row.runKey)).findFirst().orElseThrow();
        assertThat(report.phases()).singleElement().satisfies(p -> assertThat(p.state()).isEqualTo("RUNNING"));
    }
}
