package com.eap.platform.workflow;

import com.eap.platform.agent.*;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class WorkflowExecutionConfigurationIntegrationTests {
    @Autowired WorkflowExecutionConfigurationService service;
    @Autowired WorkflowExecutionRepository executions;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired AgentRepository agents;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    long workflow, agent;
    String run, instance, uid, deployment;
    WorkflowExecution execution;
    @BeforeEach void fixture() throws Exception {
        String code="CONFIG-"+UUID.randomUUID();
        var a=new Agent();a.setCode(code);a.setName("Public fixture");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.saveAndFlush(a).getId();
        db.update("insert into workflow_definitions(code,name,status,created_at,updated_at) values(?,'Public fixture','DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",code);
        workflow=db.queryForObject("select id from workflow_definitions where code=?",Long.class,code);
        run="wf-"+UUID.randomUUID();instance="test/a-"+UUID.randomUUID();uid=UUID.randomUUID().toString();deployment="dep-"+UUID.randomUUID();
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,status,load_score,dispatch_authenticated,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE','ONLINE',0,true,?,'test/original-template',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,"substrate://"+instance,uid);
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,requested_by,steps_json,created_at,updated_at) values(?,?,3,'RUNNER','READY',?,?,'test','original-template','owner','[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",deployment,agent,"{\"systemPrompt\":\"CONFIG_BODY_SENTINEL\",\"env\":{\"PRIVATE\":\"ENV_BODY_SENTINEL\"}}","registry.invalid/test@sha256:"+"a".repeat(64));
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,delivery_request) values(?,?,?,?,?,?)",agent,UUID.randomUUID().toString(),deployment,instance,uid,UUID.randomUUID().toString());
        execution=new WorkflowExecution();execution.workflowId=workflow;execution.runKey=run;execution.status="EXECUTION_UNKNOWN";execution.objective="OBJECTIVE_BODY_SENTINEL";execution.requester="owner";execution.createdAt=Instant.now();
        execution.snapshotJson=mapper.writeValueAsString(new WorkflowExecutionService.Snapshot(List.of(node("one","AGENT","{}",7L)),List.of(),Map.of("body","CONTEXT_BODY_SENTINEL")));
        executions.saveAndFlush(execution);
    }
    WorkflowDtos.NodeResponse node(String key,String type,String config,Long version) {
        return new WorkflowDtos.NodeResponse(1L,workflow,key,"Public label",type,agent,null,0,0,config,Instant.now(),Instant.now(),version);
    }
    WorkflowExecutionService.Step step(WorkflowExecutionConfigurationService.RuntimeBinding binding) throws Exception {
        return new WorkflowExecutionService.Step(0,"one","Public label",agent,run+":0",instance,"EXECUTION_UNKNOWN","ALLOW","Public reason",null,mapper.readTree("{\"body\":\"OUTPUT_BODY_SENTINEL\"}"),"public-hash").bound(binding);
    }
    void saveSteps(List<WorkflowExecutionService.Step> steps) throws Exception { execution.stepsJson=mapper.writeValueAsString(steps);executions.saveAndFlush(execution); }
    WorkflowExecutionConfigurationService.RuntimeBinding capture() { return service.capture(runtimes.findByInstanceKey(instance).orElseThrow()); }

    @Test void exactOwnershipIsCapturedAndLaterRegistryOrDeploymentChangesCannotRewriteOriginalVersion() throws Exception {
        var binding=capture();saveSteps(List.of(step(binding)));
        assertThat(binding.deploymentKey()).isEqualTo(deployment);assertThat(binding.deploymentVersion()).isEqualTo(3);assertThat(binding.imageDigest()).isEqualTo("sha256:"+"a".repeat(64));
        db.update("update substrate_agent_deployments set version=99,template_name='new-template',image_ref='registry.invalid/new:latest' where deployment_key=?",deployment);
        db.update("update agent_runtime_instances set platform_actor_uid=?,platform_actor_template='test/new-template' where instance_key=?",UUID.randomUUID().toString(),instance);
        var record=service.get(workflow,run).nodes().getFirst();
        assertThat(record.nodeVersion()).isEqualTo(7);assertThat(record.bindingState()).isEqualTo("DEPLOYMENT_RECORDED");assertThat(record.runtime()).isEqualTo(binding);
        var before=db.queryForMap("select * from workflow_executions where id=?",execution.id);
        long budgets=db.queryForObject("select count(*) from budget_reservations",Long.class);
        service.get(workflow,run);service.get(workflow,run);
        assertThat(db.queryForMap("select * from workflow_executions where id=?",execution.id)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from budget_reservations",Long.class)).isEqualTo(budgets);
    }
    @ParameterizedTest @ValueSource(strings={"uid","template","space","agent","instance"})
    void similarNamesOrConflictingOwnershipNeverEstablishDeployment(String mismatch) {
        switch(mismatch) {
            case "uid" -> db.update("update platform_runtime_creations set actor_uid=? where instance_key=?",UUID.randomUUID().toString(),instance);
            case "template" -> db.update("update substrate_agent_deployments set template_name='other' where deployment_key=?",deployment);
            case "space" -> db.update("update substrate_agent_deployments set atespace='other' where deployment_key=?",deployment);
            case "agent" -> db.update("update platform_runtime_creations set agent_id=? where instance_key=?",agent+999,instance);
            case "instance" -> db.update("update platform_runtime_creations set instance_key='test/other' where instance_key=?",instance);
        }
        var binding=capture();assertThat(binding.instanceKey()).isEqualTo(instance);assertThat(binding.actorUid()).isEqualTo(uid);
        assertThat(binding.deploymentKey()).isNull();assertThat(binding.deploymentVersion()).isNull();assertThat(binding.imageDigest()).isNull();
    }
    @Test void mutableImageTagIsNotPresentedAsFrozenDigest() {
        db.update("update substrate_agent_deployments set image_ref='registry.invalid/test:latest' where deployment_key=?",deployment);
        assertThat(capture().imageDigest()).isNull();assertThat(capture().deploymentVersion()).isEqualTo(3);
    }
    @Test void legacyRecordNeverUsesPresentDayRuntimeOrDeploymentAndMissingNodeVersionStaysMissing() throws Exception {
        saveSteps(List.of(step(null)));
        var old=service.get(workflow,run).nodes().getFirst();assertThat(old.runtime()).isNull();assertThat(old.bindingState()).isEqualTo("NOT_RECORDED");
        execution.snapshotJson=mapper.writeValueAsString(new WorkflowExecutionService.Snapshot(List.of(node("one","AGENT","{}",null)),List.of(),Map.of()));
        executions.saveAndFlush(execution);assertThat(service.get(workflow,run).nodes().getFirst().nodeVersion()).isNull();
    }
    @Test void axAndControlNodesDoNotPretendToHaveRuntimeVersions() throws Exception {
        execution.snapshotJson=mapper.writeValueAsString(new WorkflowExecutionService.Snapshot(List.of(node("control","START","{}",0L),node("async","AGENT","{\"executionBackend\":\"AX\"}",1L)),List.of(),Map.of()));
        executions.saveAndFlush(execution);assertThat(service.get(workflow,run).nodes()).extracting(WorkflowExecutionConfigurationService.Node::bindingState).containsExactly("NOT_APPLICABLE","AX_NOT_CAPTURED");
    }
    @Test void originalBindingSurvivesReclaimAndMetadataProjectionWithoutOutput() throws Exception {
        var binding=capture();var original=step(binding);var reclaimed=original.reclaimed("SKIPPED_BUSY").metadata();
        assertThat(reclaimed.runtimeBinding()).isEqualTo(binding);assertThat(reclaimed.output()).isNull();assertThat(reclaimed.outputSha256()).isEqualTo("public-hash");
        saveSteps(List.of(reclaimed));assertThat(service.get(workflow,run).nodes().getFirst().runtime()).isEqualTo(binding);
    }
    @Test void wrongRunCorruptSnapshotOrCrossNodeReceiptFailsWithoutFallback() throws Exception {
        assertThatThrownBy(()->service.get(workflow+1,run)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.get(workflow,"absent")).isInstanceOf(BusinessException.class);
        saveSteps(List.of(new WorkflowExecutionService.Step(0,"other","Public",agent,run+":0",instance,"SUCCEEDED","ALLOW","Public",200).bound(capture())));
        assertThatThrownBy(()->service.get(workflow,run)).isInstanceOf(BusinessException.class);
        execution.snapshotJson="invalid";executions.saveAndFlush(execution);
        assertThatThrownBy(()->service.get(workflow,run)).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void consoleMetadataNeverContainsConfigContextOrOutputBodies(String role) throws Exception {
        saveSteps(List.of(step(capture())));String path="/api/workflows/"+workflow+"/executions/"+run+"/configuration";
        String body=mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contract").value("EXECUTION_CONFIGURATION_V1")).andExpect(jsonPath("$.data.nodes[0].runtime.deploymentVersion").value(3)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("BODY_SENTINEL","configJson","snapshotJson","stepsJson","systemPrompt","registry.invalid");
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
    }
}
