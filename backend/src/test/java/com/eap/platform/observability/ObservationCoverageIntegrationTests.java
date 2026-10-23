package com.eap.platform.observability;

import com.eap.platform.agent.*;
import com.eap.platform.common.BusinessException;
import com.eap.platform.ax.*;
import com.eap.platform.safety.*;
import com.eap.platform.substrate.*;
import com.eap.platform.registry.RuntimeScopedTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.ax.enabled=true","eap.ax.worker-enabled=false",
        "eap.ax.runner-image=localhost:5001/test@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "eap.workflow.execution.enabled=true","eap.workflow.execution.worker-enabled=false","eap.workflow.execution.token=test-only",
        "eap.workflow.execution.allowed-hosts=localhost","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class ObservationCoverageIntegrationTests {
    @Autowired ObservationCoverageService coverage;
    @Autowired AxService ax;
    @Autowired AgentRepository agents;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired OtlpTraceIngestionService ingestion;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean AxClient client;
    @MockitoBean SubstrateClient substrate;
    @MockitoBean GenseeSafetyService safety;
    long agent;
    @BeforeEach void setup() {
        db.update("delete from observation_operation_events");db.update("delete from observation_operations");
        db.update("delete from ax_tasks");
        var a=new Agent();a.setCode("COVERAGE-"+UUID.randomUUID());a.setName("Public fixture");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.save(a).getId();
        when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,new SafetyDtos.DecisionResponse(1L,1L,"test",SafetyDecision.ALLOW,null,null,"test",null,null,null)));
    }
    long submit(String key,String mode) {ax.submit(new AxService.Submit(agent,key,"PUBLIC_FIXTURE",mode),"original-owner");return db.queryForObject("select max(id) from observation_operations",Long.class);}
    ObservationCoverageService.Operation get(long id) {return coverage.detail(id).operation();}
    void dispatched(long id,String status) {
        var o=db.queryForMap("select * from observation_operations where id=?",id);String key="eap-ax/"+o.get("operation_key");
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,last_heartbeat_at,created_at,updated_at,platform_actor_uid,platform_actor_template) values(?,?,?,'SUBSTRATE_TASK',true,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'original-uid','original-template')",agent,key,"substrate://"+key);
        long runtime=db.queryForObject("select id from agent_runtime_instances where instance_key=?",Long.class,key);
        String trace=RuntimeScopedTelemetry.trace(key,(String)o.get("raw_trace_id"));
        db.update("update ax_tasks set identity_mode='PER_INSTANCE_V1',instance_key=?,actor_uid='original-uid',actor_template='original-template',status=? where id=?",key,status,o.get("ax_task_id"));
        db.update("update observation_operations set instance_key=?,trace_id=? where id=?",key,trace,id);
        new TransactionTemplate(manager).executeWithoutResult(s->coverage.axDispatch(((Number)o.get("ax_task_id")).longValue()));
        assertThat(db.queryForObject("select runtime_id from observation_operations where id=?",Long.class,id)).isEqualTo(runtime);
    }
    void span(long id,String spanId)throws Exception {
        var o=db.queryForMap("select * from observation_operations where id=?",id);
        ingestion.ingestScoped(json.readTree("{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":[{\"traceId\":\""+o.get("trace_id")+"\",\"spanId\":\""+spanId+"\",\"name\":\"ax.task\",\"attributes\":{\"agent.runtime\":\"ax\"},\"status\":{\"code\":1}}]}]}]}"),agent,"runtime-"+o.get("runtime_id"));
    }
    @Test void originalSubmissionFreezesTraceAndReplayCannotCreateDenominatorOrEvents() {
        long id=submit("original","ECHO");var before=get(id);ax.submit(new AxService.Submit(agent,"original","PUBLIC_FIXTURE","ECHO"),"original-owner");
        assertThat(get(id).traceId()).isEqualTo(before.traceId());assertThat(db.queryForObject("select count(*) from observation_operations",Long.class)).isEqualTo(1);
        assertThat(coverage.detail(id).logs()).hasSize(1);assertThat(before.expectedModelEvents()).isZero();
        assertThatThrownBy(()->ax.submit(new AxService.Submit(agent,"original","CHANGED","ECHO"),"original-owner")).isInstanceOf(com.eap.platform.common.ConflictException.class);
        verifyNoInteractions(client,substrate);
    }
    @Test void missingEchoSpanIsNotCompleteJustBecauseExpectedModelsIsZero()throws Exception {
        long id=submit("missing","ECHO");dispatched(id,"SUCCEEDED");assertThat(get(id).coverage()).isEqualTo("MISSING_AGENT");
        span(id,"1234567890abcdef");assertThat(get(id).coverage()).isEqualTo("COMPLETE");assertThat(get(id).modelEvents()).isZero();
        span(id,"1234567890abcdef");assertThat(get(id).agentSpans()).isEqualTo(1);span(id,"abcdef1234567890");assertThat(get(id).coverage()).isEqualTo("DUPLICATE_EVIDENCE");
    }
    @Test void failedAndUnknownOperationsRemainInDenominatorEvenWhenEvidenceIsComplete()throws Exception {
        for(String status:List.of("FAILED","EXECUTION_UNKNOWN")) {long id=submit(status,"FAIL");dispatched(id,status);span(id,"1234567890abcdef");assertThat(get(id).coverage()).isEqualTo("COMPLETE");assertThat(get(id).executionStatus()).isEqualTo(status);}
        var metric=coverage.report(60).metrics().stream().filter(m->m.entryKind().equals("AX_FAIL")).findFirst().orElseThrow();
        assertThat(metric.registered()).isEqualTo(2);assertThat(metric.failed()).isEqualTo(1);assertThat(metric.unknown()).isEqualTo(1);
    }
    @Test void changedUidCannotBorrowFrozenEvidenceAndReadDoesNotRepairIdentity()throws Exception {
        long id=submit("uid","ECHO");dispatched(id,"SUCCEEDED");span(id,"1234567890abcdef");
        db.update("update agent_runtime_instances set platform_actor_uid='replacement-uid' where id=(select runtime_id from observation_operations where id=?)",id);
        assertThat(get(id).coverage()).isEqualTo("IDENTITY_UNCONFIRMED");assertThat(get(id).agentSpans()).isNull();assertThat(get(id).modelEvents()).isNull();
        assertThat(db.queryForObject("select actor_uid from observation_operations where id=?",String.class,id)).isEqualTo("original-uid");verifyNoInteractions(client,substrate);
    }
    @Test void mismatchedReceiptTraceIsVisibleWithoutChangingBusinessReceipt()throws Exception {
        long id=submit("mismatch","ECHO");dispatched(id,"SUCCEEDED");span(id,"1234567890abcdef");
        long task=db.queryForObject("select ax_task_id from observation_operations where id=?",Long.class,id);
        new TransactionTemplate(manager).executeWithoutResult(s->coverage.axReceipt(task,json.createObjectNode().put("runtimeTraceId","b".repeat(32))));
        assertThat(get(id).coverage()).isEqualTo("RECEIPT_TRACE_UNCONFIRMED");assertThat(get(id).executionStatus()).isEqualTo("SUCCEEDED");
    }
    @Test void llmNeedsGatewayEventAndCannotTreatEchoRulesAsUniversal()throws Exception {
        long id=submit("llm","LLM");dispatched(id,"SUCCEEDED");span(id,"1234567890abcdef");
        assertThat(get(id).expectedModelEvents()).isEqualTo(1);assertThat(get(id).coverage()).isEqualTo("MISSING_MODEL");
    }
    @Test void oldTaskReadsNeverEnrollAndUnregisteredEntrypointsHaveNullDenominators() {
        long id=submit("old","ECHO");String key=get(id).operationKey();db.update("delete from observation_operations where id=?",id);
        ax.get(key);ax.content(key);coverage.report(60);assertThat(db.queryForObject("select count(*) from observation_operations",Long.class)).isZero();
        assertThat(coverage.report(60).metrics().stream().filter(m->List.of("CHAT","RUNTIME_TASK","LEGACY_EXTERNAL").contains(m.entryKind()))).allSatisfy(m->{assertThat(m.registered()).isNull();assertThat(m.expectedModelEvents()).isNull();});
    }
    @Test void enrollmentRollbackDoesNotLeaveSuccessfulMetadataOrDispatch() {
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(s->{submit("rolled-back","ECHO");throw new IllegalStateException("synthetic rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from observation_operations",Long.class)).isZero();assertThat(db.queryForObject("select count(*) from observation_operation_events",Long.class)).isZero();verifyNoInteractions(client,substrate);
    }
    @Test void totalsUseAllRegisteredRowsEvenWhenRecentDetailsAreTruncated() {
        for(int i=0;i<101;i++)submit("window-"+i,"ECHO");var report=coverage.report(60);
        assertThat(report.recent()).hasSize(100);assertThat(report.detailsTruncated()).isTrue();assertThat(report.metrics().stream().filter(m->m.entryKind().equals("AX_ECHO")).findFirst().orElseThrow().registered()).isEqualTo(101);
        assertThatThrownBy(()->coverage.report(0)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->coverage.report(10081)).isInstanceOf(BusinessException.class);
    }
}
