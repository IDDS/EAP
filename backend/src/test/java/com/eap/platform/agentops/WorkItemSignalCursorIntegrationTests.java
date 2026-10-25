package com.eap.platform.agentops;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "test.signal-cursor-isolation=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkItemSignalCursorIntegrationTests {
    @Autowired WorkItemService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentOpsSignalSyncStateRepository states;
    @Autowired PlatformTransactionManager transactions;
    private long evaluator;

    @BeforeEach void clearFixtures() {
        jdbc.update("delete from agent_work_item_events");
        jdbc.update("delete from agent_work_items");
        jdbc.update("delete from governance_preflight_decisions");
        jdbc.update("delete from governance_data_events");
        jdbc.update("delete from eap_evaluation_results");
        jdbc.update("update agentops_signal_sync_state set approval_cursor=0,approval_upper_bound=0,evaluation_cursor=0,evaluation_upper_bound=0");
        evaluator = jdbc.queryForObject("select min(id) from eap_evaluators", Long.class);
    }

    @Test void firstScanFindsOldSignalsBehindMoreThan500NoiseRecords() {
        approval("old-approval", "PENDING"); evaluation("old-failure", false, "old-trace", Instant.now().minusSeconds(900));
        for (int i = 0; i < 501; i++) {
            approval("noise-" + i, "NOT_REQUIRED"); evaluation("success-" + i, true, "noise-" + i, Instant.now());
        }
        service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(1);
        assertThat(count("EVALUATION_FAILURE")).isEqualTo(1);
        service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(1);
        assertThat(count("EVALUATION_FAILURE")).isEqualTo(1);
    }

    @Test void resumesCommittedCursorAndRevisitsChangesBelowFrozenCeiling() {
        for (int i = 0; i < 1005; i++) {
            approval("pending-" + i, "PENDING"); evaluation("failure-" + i, false, "trace-" + i, Instant.now());
        }
        service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(500);
        assertThat(count("EVALUATION_FAILURE")).isEqualTo(500);
        var state = states.findById(1L).orElseThrow();
        assertThat(state.approvalCursor).isPositive();
        long ceiling = state.approvalUpperBound;
        approval("arrived-during-cycle", "PENDING");
        service.synchronizeSignals();
        assertThat(states.findById(1L).orElseThrow().approvalUpperBound).isEqualTo(ceiling);
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(1000);
        service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(1005);
        assertThat(count("EVALUATION_FAILURE")).isEqualTo(1005);
        assertThat(states.findById(1L).orElseThrow().approvalCursor).isZero();
        jdbc.update("update governance_preflight_decisions set approval_status='APPROVED' where decision_key='pending-0'");
        service.synchronizeSignals();
        assertThat(jdbc.queryForObject("select status from agent_work_items where source_id='pending-0'", String.class)).isEqualTo("RESOLVED");
        jdbc.update("update governance_preflight_decisions set approval_status='PENDING' where decision_key='pending-0'");
        for (int i = 0; i < 4; i++) service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(1006);
        assertThat(jdbc.queryForObject("select status from agent_work_items where source_id='pending-0'", String.class)).isEqualTo("OPEN");
    }

    @Test void rollbackDoesNotAdvanceCursorOrLeavePartialItems() {
        for (int i = 0; i < 501; i++) approval("rollback-" + i, "PENDING");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            service.synchronizeSignals(); throw new IllegalStateException("synthetic interruption");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("GOVERNANCE_APPROVAL")).isZero();
        assertThat(states.findById(1L).orElseThrow().approvalCursor).isZero();
        service.synchronizeSignals(); service.synchronizeSignals();
        assertThat(count("GOVERNANCE_APPROVAL")).isEqualTo(501);
    }

    @Test void revisitingEarlierFailureNeverReplacesNewerEvidenceForSameTrace() {
        evaluation("new-evidence", false, "same-trace", Instant.now());
        evaluation("old-evidence", false, "same-trace", Instant.now().minusSeconds(100));
        service.synchronizeSignals(); service.synchronizeSignals();
        assertThat(count("EVALUATION_FAILURE")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source_id from agent_work_items where item_type='EVALUATION_FAILURE'", String.class))
                .isEqualTo("new-evidence");
    }

    private long count(String type) { return jdbc.queryForObject("select count(*) from agent_work_items where item_type=?", Long.class, type); }
    private void approval(String key, String status) {
        jdbc.update("insert into governance_data_events(event_key,direction,data_category,sensitivity,action,internal_data,company_secret,token_count,payload_bytes,observed_at,created_at) values(?,'INPUT_TO_MODEL','PUBLIC','PUBLIC','ALLOW',false,false,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", key);
        long event = jdbc.queryForObject("select id from governance_data_events where event_key=?", Long.class, key);
        jdbc.update("insert into governance_preflight_decisions(decision_key,data_event_id,action,gateway_directive,request_allowed,approval_status,matched_policy_count,classifier,request_fingerprint,decided_at) values(?,?,'ALLOW','PROCEED',true,?,0,'fixture',?,CURRENT_TIMESTAMP)", key,event,status,UUID.randomUUID().toString());
    }
    private void evaluation(String key, boolean passed, String trace, Instant at) {
        jdbc.update("insert into eap_evaluation_results(result_key,evaluator_id,evaluator_version,trace_id,level,status,score,passed,confidence,evaluated_at,created_at) values(?,?,'v1',?,'SPAN','COMPLETED',0,?,1,?,CURRENT_TIMESTAMP)", key,evaluator,trace,passed,Timestamp.from(at));
    }
}
