package com.eap.platform.operations;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.eap.platform.common.BusinessException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskEvidenceServiceTests {
    private JdbcTemplate jdbc;
    private TaskEvidenceService service;
    @BeforeEach void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:evidence_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO agent_tasks(id,agent_id,task_type,status,created_at,updated_at) VALUES (800,1,'TEST','SUCCEEDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(801,1,'OTHER','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var repo = mock(AgentTaskRepository.class);
        AgentTask task = new AgentTask(); task.id=800L; task.agentId=1L; task.taskType="TEST"; task.status=AgentTaskStatus.SUCCEEDED;
        when(repo.findById(800L)).thenReturn(Optional.of(task));
        service = new TaskEvidenceService(repo, jdbc);
    }
    private void tool(String key, Long agent, Long task) {
        jdbc.update("INSERT INTO tool_invocation_events(invocation_key,trace_id,agent_id,task_id,observation_source,tool_kind,tool_name,status,duration_ms,observed_at,created_at,updated_at) VALUES (?,'shared-trace',?,?,'OTLP','TOOL','search','SUCCEEDED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",key,agent,task);
    }
    @Test void linksOnlyExplicitTaskIdsAndIsolatesConflictingAgents() {
        tool("match",1L,800L);tool("unknown-agent",null,800L);tool("conflict",2L,800L);
        tool("same-agent-other-task",1L,801L);tool("same-trace-no-task",1L,null);
        var result=service.get(800);
        var tools=result.sections().get(1);
        assertThat(tools.records()).extracting(TaskEvidenceService.Evidence::key).containsExactly("unknown-agent","match");
        assertThat(tools.conflictingAgentRecords()).isEqualTo(1);
        assertThat(result.task().problemResolved()).isNull();
        assertThat(result.task().status()).isEqualTo(AgentTaskStatus.SUCCEEDED);
        assertThat(result.associationBasis()).isEqualTo("EXPLICIT_TASK_ID");
    }
    @Test void readsAllSourceSchemasAndPreservesCurrencyWithoutAggregation() {
        jdbc.update("INSERT INTO model_invocation_events(event_key,trace_id,agent_id,task_id,provider,model_name,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,latency_ms,money_cost,currency,tool_call_count,mcp_server_count,observed_at,created_at) VALUES ('model','trace-m',1,800,'test','model-a','SUCCEEDED',1,2,0,0,50,2,'USD',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO governance_data_events(event_key,agent_id,task_id,trace_id,direction,data_category,sensitivity,action,internal_data,company_secret,token_count,payload_bytes,observed_at,created_at) VALUES ('gov',1,800,'trace-m','INPUT_TO_MODEL','test','PUBLIC','BLOCK',false,false,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO management_value_events(event_key,agent_id,task_id,value_type,validated_by,benefit_money,currency,observed_at,created_at) VALUES ('value',1,800,'BENEFIT','tester',3,'CNY',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var sections=service.get(800).sections();
        assertThat(sections.get(0).records().getFirst().currency()).isEqualTo("USD");
        assertThat(sections.get(2).records().getFirst().status()).isEqualTo("BLOCK");
        assertThat(sections.get(3).records().getFirst().currency()).isEqualTo("CNY");
        assertThat(sections.get(3).records().getFirst().traceId()).isNull();
    }
    @Test void signalsTruncationAndDoesNotReturnUnboundedHistory() {
        for(int i=0;i<101;i++)tool("tool-"+i,1L,800L);
        var section=service.get(800).sections().get(1);
        assertThat(section.records()).hasSize(100);
        assertThat(section.hasMore()).isTrue();
        assertThat(section.records().getFirst().key()).isEqualTo("tool-100");
    }
    @Test void missingTaskDoesNotExposeUnrelatedEvidence() {
        assertThatThrownBy(()->service.get(999)).isInstanceOf(BusinessException.class);
    }
}
