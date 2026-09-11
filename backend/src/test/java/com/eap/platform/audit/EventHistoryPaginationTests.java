package com.eap.platform.audit;
import com.eap.platform.common.EventHistoryQuery;
import com.eap.platform.management.ManagementService;
import com.eap.platform.governance.GovernanceDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.workflow.execution.worker-enabled=false","eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
@Transactional
class EventHistoryPaginationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ManagementService management;
    @Autowired GovernanceDataService governance;
    @Test void valueHistoryExceedsCapAndIncludesContributorsWithoutDuplicatingRows(){
        for(int i=0;i<505;i++) jdbc.update("INSERT INTO management_value_events(event_key,agent_id,endpoint_key,value_type,description,validated_by,benefit_money,currency,observed_at,created_at) VALUES(?,1,'history-endpoint','TEST','event-history-fixture','tester',1,'USD','2024-01-01 00:00:00','2026-01-01 00:00:00')","history-value-"+i);
        long id=jdbc.queryForObject("SELECT id FROM management_value_events WHERE event_key='history-value-0'",Long.class);
        jdbc.update("INSERT INTO management_value_attributions(value_event_id,agent_id,contribution_weight,attribution_method,created_at) VALUES(?,2,1,'BUSINESS_CONFIRMED',CURRENT_TIMESTAMP)",id);
        var q=new EventHistoryQuery();q.keyword="event-history-fixture";q.page=6;q.size=100;
        var page=management.pageValues(q);assertThat(page.totalElements()).isEqualTo(505);assertThat(page.items()).hasSize(5);
        q.page=1;q.setAgentId(2L);var attributed=management.pageValues(q);assertThat(attributed.totalElements()).isEqualTo(1);assertThat(attributed.items().getFirst().agentContributions()).hasSize(1);
        q.setCurrency("eur");assertThat(management.pageValues(q).totalElements()).isZero();
        q.setCurrency(" usd ");q.from=Instant.parse("2025-01-01T00:00:00Z");assertThat(management.pageValues(q).totalElements()).isZero();
        q.from=null;q.setEndpointKey("other");assertThat(management.pageValues(q).totalElements()).isZero();
    }
    @Test void governanceFiltersEventContextAndObservedTime(){
        for(int i=0;i<111;i++) jdbc.update("INSERT INTO governance_data_events(event_key,agent_id,endpoint_key,trace_id,direction,data_category,sensitivity,action,internal_data,company_secret,token_count,payload_bytes,observed_at,created_at) VALUES(?,1,'history-endpoint','history%_trace','INPUT_TO_MODEL','TEST','PUBLIC','ALLOW',false,false,0,0,'2024-01-01 00:00:00','2026-01-01 00:00:00')","history-governance-"+i);
        var q=new EventHistoryQuery();q.keyword="history%_trace";q.page=2;q.size=100;q.status="ALLOW";
        assertThat(governance.page(q).totalElements()).isEqualTo(111);assertThat(governance.page(q).items()).hasSize(11);
        q.page=1;q.setAgentId(2L);assertThat(governance.page(q).totalElements()).isZero();
        q.setAgentId(1L);q.setEventKey("history-governance-1");assertThat(governance.page(q).totalElements()).isEqualTo(1);
        q.from=Instant.parse("2025-01-01T00:00:00Z");assertThat(governance.page(q).totalElements()).isZero();
        q.from=null;q.status="INVALID";assertThatThrownBy(()->governance.page(q)).hasMessageContaining("无效");
    }
}
