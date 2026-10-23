package com.eap.platform.audit;
import com.eap.platform.common.*;
import com.eap.platform.observability.*;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.eap.platform.governance.GovernancePreflightService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.workflow.execution.worker-enabled=false","eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test") @Transactional
class PhysicalHistoryTests {
 @Autowired JdbcTemplate jdbc;
 @Autowired ToolInvocationService tools;
 @Autowired OtlpTraceIngestionService spans;
 @Autowired ContentObservationController contents;
 @Autowired ModelTelemetryService models;
 @Autowired GovernancePreflightService decisions;
 @Test void physicalHistoryPagesAllFourTypesWithoutLeakingDevelopmentContent(){
  for(int i=0;i<105;i++){
   jdbc.update("INSERT INTO tool_invocation_events(invocation_key,trace_id,agent_id,endpoint_key,observation_source,tool_kind,tool_name,status,duration_ms,observed_at,created_at,updated_at) VALUES(?,'physical-history',1,'test-endpoint','OTLP','TOOL','lookup','SUCCEEDED',1,'2024-01-01','2026-01-01','2026-01-01')","physical-tool-"+i);
   jdbc.update("INSERT INTO trace_span_observations(trace_id,span_id,agent_id,endpoint_key,span_name,span_kind,status,duration_ms,observed_at,created_at) VALUES('physical-history',?,1,'test-endpoint','lookup','TOOL','SUCCEEDED',1,'2024-01-01','2026-01-01')","physical-span-"+i);
   jdbc.update("INSERT INTO content_observations(observation_key,trace_id,agent_id,endpoint_key,direction,capture_mode,content_hash,content_bytes,estimated_tokens,content_json,redaction_applied,observed_at,created_at) VALUES(?,'physical-history',1,'test-endpoint','INPUT','REDACTED','hash',12,3,'private-test-text',true,'2024-01-01','2026-01-01')","physical-content-"+i);
   jdbc.update("INSERT INTO model_invocation_events(event_key,trace_id,agent_id,endpoint_key,provider,model_name,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,latency_ms,money_cost,currency,tool_call_count,mcp_server_count,observed_at,created_at) VALUES(?,'physical-history',1,'test-endpoint','test','test','SUCCEEDED',0,0,0,0,1,0,'USD',0,0,'2024-01-01','2026-01-01')","physical-model-"+i);
  }
  var q=new ObservationHistoryQuery();q.setTraceId("physical-history");q.size=100;q.page=2;
  assertThat(tools.page(q).totalElements()).isEqualTo(105);assertThat(tools.page(q).items()).hasSize(5);
  assertThat(spans.page(q).items()).hasSize(5);assertThat(models.page(q).items()).hasSize(5);
  var content=contents.page(q).data();assertThat(content.items()).hasSize(5);assertThat(content.items()).allSatisfy(row->assertThat(row.contentJson()).isNull());
  q.from=Instant.parse("2025-01-01T00:00:00Z");assertThat(tools.page(q).totalElements()).isZero();assertThat(models.page(q).totalElements()).isZero();
  q.from=null;q.setAgentId(2L);assertThat(spans.page(q).totalElements()).isZero();assertThat(contents.page(q).data().totalElements()).isZero();
 }
 @Test void decisionHistoryFindsLinkedEventsBeyondRecentWindow(){
  for(int i=0;i<105;i++){
   jdbc.update("INSERT INTO governance_data_events(event_key,agent_id,endpoint_key,trace_id,direction,data_category,sensitivity,action,internal_data,company_secret,token_count,payload_bytes,observed_at,created_at) VALUES(?,1,'test-endpoint','decision-history','INPUT_TO_MODEL','TEST','PUBLIC','ALLOW',false,false,0,0,'2024-01-01','2026-01-01')","decision-event-"+i);
   long id=jdbc.queryForObject("SELECT id FROM governance_data_events WHERE event_key=?",Long.class,"decision-event-"+i);
   jdbc.update("INSERT INTO governance_preflight_decisions(decision_key,data_event_id,action,gateway_directive,request_allowed,matched_policy_count,classifier,approval_status,decided_at) VALUES(?,?,'ALLOW','PROCEED',true,0,'test','NOT_REQUIRED','2024-01-01')","decision-history-"+i,id);
  }
  var q=new DecisionHistoryQuery();q.setTraceId("decision-history");q.page=2;q.size=100;
  assertThat(decisions.page(q).totalElements()).isEqualTo(105);assertThat(decisions.page(q).items()).hasSize(5);
  q.page=1;q.setEventKey("decision-event-0");assertThat(decisions.page(q).totalElements()).isEqualTo(1);
  q.status="PENDING";assertThat(decisions.page(q).totalElements()).isZero();
 }
}
