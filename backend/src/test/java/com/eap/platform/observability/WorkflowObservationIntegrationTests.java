package com.eap.platform.observability;

import com.eap.platform.agent.*;
import com.eap.platform.common.BusinessException;
import com.eap.platform.config.SecurityProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class WorkflowObservationIntegrationTests {
    @Autowired WorkflowObservationService service;
    @Autowired ObservationCoverageService coverage;
    @Autowired JdbcTemplate db;
    @Autowired AgentRepository agents;
    @Autowired SecurityProperties security;
    @Autowired MockMvc mvc;
    long workflow, execution, agent;
    String run;
    @BeforeEach void fixture() {
        String code="WF-OBS-"+UUID.randomUUID();
        db.update("insert into workflow_definitions(code,name,status,created_at,updated_at) values(?,'Public fixture','DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",code);
        workflow=db.queryForObject("select id from workflow_definitions where code=?",Long.class,code);
        run="wf-"+UUID.randomUUID(); execution=execution(run);
        var a=new Agent();a.setCode(code);a.setName("Public fixture");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.saveAndFlush(a).getId();
    }
    long execution(String key) {
        db.update("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values(?,?,'EXECUTION_UNKNOWN','PUBLIC_BODY_SENTINEL','owner','PUBLIC_SNAPSHOT_SENTINEL','[]',CURRENT_TIMESTAMP)",workflow,key);
        return db.queryForObject("select id from workflow_executions where run_key=?",Long.class,key);
    }
    long operation(long parent,String kind,int sequence) {
        String key="public-"+UUID.randomUUID();
        db.update("insert into observation_operations(entry_kind,operation_key,workflow_execution_id,node_sequence,agent_id,registration_state,expected_agent_spans,expected_model_events,created_at) values(?,?,?,?,?,'PENDING_IDENTITY',1,1,?)",kind,key,parent,sequence,agent,Timestamp.from(Instant.now().minusSeconds(31L*86400)));
        return db.queryForObject("select id from observation_operations where operation_key=?",Long.class,key);
    }
    long ax(String parentRun,Long parentWorkflow,Long observationParent) {
        String key="ax-"+UUID.randomUUID();
        db.update("insert into ax_tasks(task_key,submission_key,agent_id,fingerprint,requested_by,status,phase,reason,payload_json,result_json,workflow_id,workflow_run_key,workflow_node_key,created_at,updated_at,deadline_at) values(?,?,?,'public','owner','EXECUTION_UNKNOWN','Unknown','public','PUBLIC_AX_INPUT_SENTINEL','PUBLIC_AX_RESULT_SENTINEL',?,?,'ax-node',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",key,key,agent,parentWorkflow,parentRun);
        long task=db.queryForObject("select id from ax_tasks where task_key=?",Long.class,key);
        db.update("insert into observation_operations(entry_kind,operation_key,ax_task_id,workflow_execution_id,agent_id,registration_state,expected_agent_spans,expected_model_events,created_at) values('AX_LLM',?,?,?,?,'PENDING_IDENTITY',1,1,CURRENT_TIMESTAMP)",key,task,observationParent,agent);
        return db.queryForObject("select id from observation_operations where operation_key=?",Long.class,key);
    }
    @Test void pagesAllOriginalHistoryBeyondGlobalWindowAndRecentLimit() {
        List<Long> ids=new ArrayList<>();for(int i=0;i<105;i++)ids.add(operation(execution,"RUNTIME_TASK",i));
        long other=execution("other-"+UUID.randomUUID());for(int i=0;i<101;i++)operation(other,"RUNTIME_TASK",i);
        assertThat(coverage.report(10080).recent()).noneMatch(o->ids.contains(o.id()));
        List<Long> received=new ArrayList<>();String cursor=null;int pages=0;
        do {var page=service.page(workflow,run,20,cursor);assertThat(page.entries()).hasSizeLessThanOrEqualTo(20);page.entries().forEach(e->received.add(e.operation().id()));cursor=page.nextCursor();pages++;}while(cursor!=null&&pages<10);
        Collections.reverse(ids);assertThat(received).containsExactlyElementsOf(ids);assertThat(pages).isEqualTo(6);
    }
    @Test void exactParentsIncludeControlsAndAxBeforeProgressReceiptButExcludeNearbyKeysAndConflicts() {
        long control=operation(execution,"WORKFLOW_CONTROL",0),runtime=operation(execution,"RUNTIME_TASK",1),ax=ax(run,workflow,null);
        long other=execution("other-"+UUID.randomUUID());long unrelated=operation(other,"RUNTIME_TASK",1);
        db.update("update observation_operations set operation_key=? where id=?",run+":1",unrelated);
        ax(run+"-suffix",workflow,null);ax(run,workflow+999,null);ax(run,workflow,other);ax(null,null,null);
        operation(execution,"CHAT",2);
        var page=service.page(workflow,run,20,null);
        assertThat(page.entries().stream().map(e->e.operation().id())).containsExactly(ax,runtime,control);
        assertThat(page.entries().getFirst().nodeKey()).isEqualTo("ax-node");assertThat(page.entries().getLast().nodeSequence()).isZero();
        assertThatThrownBy(()->service.detail(workflow,run,unrelated)).isInstanceOf(BusinessException.class);
    }
    @Test void cursorIsStableAcrossNewInsertsAndBoundToOriginalExecution() {
        long first=operation(execution,"WORKFLOW_CONTROL",0),second=operation(execution,"RUNTIME_TASK",1);
        var page=service.page(workflow,run,1,null);assertThat(page.entries().getFirst().operation().id()).isEqualTo(second);
        long newest=operation(execution,"RUNTIME_TASK",2);
        var next=service.page(workflow,run,1,page.nextCursor());assertThat(next.entries().getFirst().operation().id()).isEqualTo(first);assertThat(next.nextCursor()).isNull();
        assertThat(service.page(workflow,run,1,null).entries().getFirst().operation().id()).isEqualTo(newest);
        String other="other-"+UUID.randomUUID();execution(other);
        assertThatThrownBy(()->service.page(workflow,other,1,page.nextCursor())).isInstanceOf(BusinessException.class);
    }
    @Test void unknownAndAbsentCostsRemainUnconfirmedAndReadsDoNotMutateAnyOriginalState() {
        long id=operation(execution,"RUNTIME_TASK",0);
        var before=db.queryForList("select * from observation_operations where workflow_execution_id=?",execution);
        var runBefore=db.queryForMap("select * from workflow_executions where id=?",execution);
        long costs=db.queryForObject("select count(*) from budget_reservations",Long.class);
        service.page(workflow,run,20,null);var detail=service.detail(workflow,run,id).detail();
        assertThat(detail.operation().executionStatus()).isEqualTo("EXECUTION_UNKNOWN");assertThat(detail.operation().agentSpans()).isNull();assertThat(detail.costs()).isEmpty();
        assertThat(db.queryForList("select * from observation_operations where workflow_execution_id=?",execution)).isEqualTo(before);
        assertThat(db.queryForMap("select * from workflow_executions where id=?",execution)).isEqualTo(runBefore);
        assertThat(db.queryForObject("select count(*) from budget_reservations",Long.class)).isEqualTo(costs);
        assertThat(db.queryForObject("select count(*) from observation_operation_events where operation_id=?",Long.class,id)).isZero();
    }
    @Test void visibilityIsAppliedBeforePaginationAndDetailAcrossBothAssociations() {
        long visible=operation(execution,"RUNTIME_TASK",0),demo=operation(execution,"RUNTIME_TASK",1),demoAx=ax(run,workflow,null);
        long seed=db.queryForObject("select min(id) from agents where record_origin='DEMO'",Long.class);
        db.update("update observation_operations set agent_id=? where id in (?,?)",seed,demo,demoAx);
        assertThat(service.page(workflow,run,1,null).entries().getFirst().operation().id()).isEqualTo(visible);
        assertThat(service.page(workflow,run,1,null).nextCursor()).isNull();
        for(long id:List.of(demo,demoAx))assertThatThrownBy(()->service.detail(workflow,run,id)).isInstanceOf(BusinessException.class);
        try {security.setMode(SecurityProperties.Mode.DEVELOPMENT);assertThat(service.page(workflow,run,20,null).entries()).hasSize(3);}
        finally {security.setMode(SecurityProperties.Mode.JWT);}
    }
    @Test void missingRunAndInvalidCursorsFailWithoutFallbackOrEnrollment() {
        assertThat(service.page(workflow,run,20,null).entries()).isEmpty();
        assertThatThrownBy(()->service.page(workflow+999,run,20,null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.page(workflow,"absent",20,null)).isInstanceOf(BusinessException.class);
        for(String cursor:List.of("","bad!","x".repeat(81),Base64.getUrlEncoder().withoutPadding().encodeToString(("1:"+execution+":0").getBytes())))
            assertThatThrownBy(()->service.page(workflow,run,20,cursor)).isInstanceOf(BusinessException.class);
        for(int limit:List.of(0,51))assertThatThrownBy(()->service.page(workflow,run,limit,null)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void allConsoleRolesReadOnlyScopedMetadataAndNoBodies(String role)throws Exception {
        long id=ax(run,workflow,null);String path="/api/workflows/"+workflow+"/executions/"+run+"/observations";
        var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role));
        for(String url:List.of(path,path+"/"+id)) {
            String body=mvc.perform(get(url).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.runKey").value(run)).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("PUBLIC_BODY_SENTINEL","PUBLIC_SNAPSHOT_SENTINEL","PUBLIC_AX_INPUT_SENTINEL","PUBLIC_AX_RESULT_SENTINEL");
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
        }
        mvc.perform(get(path+"?limit=51").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(get(path.replace(run,"absent")+"/"+id).with(auth)).andExpect(status().isBadRequest());
    }
}
