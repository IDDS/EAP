package com.eap.platform.optimization;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.experiment.AgentExperimentClient;
import com.eap.platform.gateway.OpenAiUpstreamClient;
import com.eap.platform.registry.*;
import com.eap.platform.workflow.WorkflowTaskClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.team-evolution.contracts-enabled=true","eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid/realm","eap.security.jwk-set-uri=https://idp.invalid/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class TeamEvolutionIntegrationTests {
    @Autowired TeamEvolutionService service; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired AgentService agents; @Autowired AgentRuntimeInstanceRepository runtimes; @Autowired MockMvc mvc;
    @Autowired TeamEvolutionConsoleService console;
    @Autowired TeamEvolutionOperationService operations;
    @MockitoBean RuntimeConnectionService connections;
    @MockitoBean AgentExperimentClient experiments;
    @MockitoBean OpenAiUpstreamClient upstream;
    @MockitoBean WorkflowTaskClient workflow;
    long first, second;
    String firstInstance, secondInstance;
    @BeforeEach void setup() {
        db.update("delete from team_evolution_closures");db.update("delete from team_evolution_holdouts");db.update("delete from team_evolution_frontiers");db.update("delete from team_evolution_scores");db.update("delete from team_evolution_sample_responsibilities");
        db.update("delete from team_evolution_dispatches");db.update("delete from team_evolution_executions");
        db.update("delete from team_evolution_proposals");db.update("delete from team_evolution_budget_plans");
        for (String table : List.of("team_evolution_slots","team_evolution_evaluations","team_evolution_attempts")) db.update("delete from " + table);
        for(int generation=8;generation>0;generation--) db.update("delete from team_evolution_candidates where generation=?",generation);
        db.update("delete from team_evolution_candidates"); db.update("delete from team_evolution_contracts");
        when(connections.executable(any())).thenReturn(true);
        first = fixture("extractor"); second = fixture("classifier");
        firstInstance = "eap-agents/team-" + first; secondInstance = "eap-agents/team-" + second;
    }
    @AfterEach void noExecution() { verifyNoInteractions(experiments,upstream,workflow); verify(connections,never()).checkIdentity(any()); }
    long fixture(String role) {
        long agent = agents.create(new AgentDtos.AgentRequest("TEAM-"+UUID.randomUUID(),"Team contract","","Test","Owner","Test",RiskLevel.LOW)).id();
        agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        String instance="eap-agents/team-"+agent, template="team-template-"+agent, uid="team-uid-"+agent;
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,"substrate://"+instance,uid,"eap-agents/"+template);
        String config="{\"sourceType\":\"RUNNER\",\"systemPrompt\":\""+role+" baseline\",\"model\":\"test-model\"}";
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'RUNNER','READY',?,?,'eap-agents',?,?,'[]','fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","team-dep-"+agent,agent,config,"registry.test/runner@sha256:"+"b".repeat(64),template,instance);
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,revoked,attempted,delivery_request) values(?,?,?,?,?,false,true,?)",agent,"creation-"+agent,"team-dep-"+agent,instance,uid,UUID.randomUUID().toString());
        return agent;
    }
    ObjectNode request(String key) {
        ObjectNode r=json.createObjectNode().put("requestKey",key).put("publicSamplesConfirmed",true).put("maxCandidates",4)
                .put("maxAttempts",6).put("maxGeneration",2).put("minimumQualityBps",10000).put("maxTokens",128);
        r.set("roles",json.createArrayNode().add(json.createObjectNode().put("role","extractor").put("agentId",first).put("instanceKey",firstInstance))
                .add(json.createObjectNode().put("role","classifier").put("agentId",second).put("instanceKey",secondInstance)));
        var samples=json.createArrayNode();
        for(String split:List.of("TRAIN","VALIDATION","HOLDOUT")) samples.add(json.createObjectNode().put("id",split+"-1").put("groupId",split+"-g")
                .put("split",split).put("input","public "+split).put("expected","answer "+split));
        r.set("samples",samples); return r;
    }
    ObjectNode proposal(String key,String parent,String extractor) {
        ObjectNode r=json.createObjectNode().put("requestKey",key).put("parentId",parent);
        ObjectNode config=json.createObjectNode(); config.set("rolePrompts",json.createObjectNode().put("extractor",extractor).put("classifier","classifier baseline"));
        r.set("configuration",config);return r;
    }
    ObjectNode enrollment(String key,String candidate) { return json.createObjectNode().put("requestKey",key).put("candidateId",candidate).put("split","VALIDATION"); }
    RequestPostProcessor auth(String role,String subject) { return jwt().jwt(j->j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_"+role)); }
    long count(String table) { return db.queryForObject("select count(*) from "+table,Long.class); }

    @Test void frozenProtocolHasNoExecutionAndOriginalSubmissionIsIndependentOfCurrentTarget() {
        ObjectNode r=request("frozen"); var root=service.submit(r,"owner");
        assertThat(root.candidates()).hasSize(1); assertThat(root.executionState()).isEqualTo("DISABLED_NO_EXECUTOR");
        var content=service.content(root.id()); assertThat(json.valueToTree(content).path("protocol").path("liveIdentityState").asText()).isEqualTo("NOT_CHECKED");
        db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);
        assertThat(service.submit(r,"owner")).isEqualTo(root);
        assertThatThrownBy(()->service.submit(request("new-after-revoke"),"owner")).isInstanceOf(BusinessException.class).hasMessage("TEAM_CREATION_UNCONFIRMED");
        r.put("maxTokens",129); assertThatThrownBy(()->service.submit(r,"owner")).isInstanceOf(ConflictException.class);
        assertThat(count("team_evolution_contracts")).isEqualTo(1);
    }
    @Test void newServiceInstanceReadsOriginalCandidateAndEvaluationWithoutRepeatingWork() {
        var root=service.submit(request("restart"),"owner");
        var candidate=service.propose(root.id(),proposal("change",root.baselineId(),"new extractor"),"owner");
        var evaluation=service.enroll(root.id(),enrollment("eval",candidate.candidateId()),"owner");
        var restarted=new TeamEvolutionService(db,json,agents,runtimes,connections); restarted.enabled=true;
        assertThat(restarted.get(root.id())).isEqualTo(service.get(root.id()));
        assertThat(restarted.propose(root.id(),proposal("change",root.baselineId(),"new extractor"),"owner")).isEqualTo(candidate);
        assertThat(restarted.enroll(root.id(),enrollment("eval",candidate.candidateId()),"owner")).isEqualTo(evaluation);
        assertThat(evaluation.slots()).hasSize(2).allMatch(s->s.state().equals("NOT_DISPATCHED"));
        assertThat(evaluation.slots().stream().map(TeamEvolutionService.Slot::role).toList()).containsExactly("extractor","classifier");
        assertThat(evaluation.slots().stream().map(TeamEvolutionService.Slot::traceId).distinct().count()).isEqualTo(2);
    }
    @Test void rejectionIsDurableBoundedAndDoesNotStoreUnapprovedConfiguration() {
        var r=request("rejected");r.put("maxAttempts",1);var root=service.submit(r,"owner");
        var p=proposal("bad",root.baselineId(),"new");((ObjectNode)p.path("configuration")).put("model","UNAPPROVED_CONTENT_SENTINEL");
        var attempt=service.propose(root.id(),p,"owner");
        assertThat(attempt.disposition()).isEqualTo("REJECTED"); assertThat(attempt.candidateId()).isNull();
        assertThat(service.propose(root.id(),p,"owner")).isEqualTo(attempt);
        assertThat(service.get(root.id()).attempts()).isEqualTo(1);
        assertThatThrownBy(()->service.propose(root.id(),proposal("next",root.baselineId(),"new"),"owner")).hasMessage("TEAM_ATTEMPT_LIMIT_REACHED");
        assertThat(json.valueToTree(service.content(root.id())).toString()).doesNotContain("UNAPPROVED_CONTENT_SENTINEL");
        assertThat(count("team_evolution_candidates")).isEqualTo(1);
    }
    @Test void changedRequestKeysCannotReplaceOriginalUnknownEvaluation() {
        var root=service.submit(request("unknown"),"owner"); var r=enrollment("original",root.baselineId()); var e=service.enroll(root.id(),r,"owner");
        db.update("update team_evolution_evaluations set state='UNKNOWN' where id=?",e.id()); // isolated DB fault fixture; no dispatcher exists
        assertThat(service.enroll(root.id(),r,"owner").state()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->service.enroll(root.id(),enrollment("replacement",root.baselineId()),"owner")).isInstanceOf(ConflictException.class).hasMessage("TEAM_ORIGINAL_EVALUATION_REQUIRED");
        assertThat(count("team_evolution_evaluations")).isEqualTo(1);assertThat(count("team_evolution_slots")).isEqualTo(2);
    }
    @Test void foreignParentPrincipalAndEvaluationAreRejectedWithoutCrossContractLeak() {
        var a=service.submit(request("scope-a"),"owner"); var b=service.submit(request("scope-b"),"another-owner");
        assertThatThrownBy(()->service.propose(a.id(),proposal("foreign-owner",a.baselineId(),"new"),"another-owner")).isInstanceOf(ConflictException.class);
        assertThat(service.propose(a.id(),proposal("foreign-parent",b.baselineId(),"new"),"owner").reasonCode()).isEqualTo("TEAM_PARENT_UNCONFIRMED");
        var e=service.enroll(a.id(),enrollment("e",a.baselineId()),"owner");
        assertThatThrownBy(()->service.evaluation(b.id(),e.id())).hasMessage("TEAM_EVALUATION_NOT_FOUND");
        assertThatThrownBy(()->service.enroll(a.id(),enrollment("e2",b.baselineId()),"owner")).hasMessage("TEAM_CANDIDATE_UNCONFIRMED");
    }
    @Test void concurrentSameKeyCommitsOneAttemptAndOneEvaluation() throws Exception {
        var root=service.submit(request("concurrent"),"owner");var pool=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<TeamEvolutionService.Attempt>>();
            for(int i=0;i<4;i++) futures.add(pool.submit(()->service.propose(root.id(),proposal("same",root.baselineId(),"new"),"owner")));
            var ids=new HashSet<String>();for(var future:futures)ids.add(future.get(20,TimeUnit.SECONDS).candidateId());
            assertThat(ids).hasSize(1);assertThat(service.get(root.id()).attempts()).isEqualTo(1);
            var evaluations=new ArrayList<Future<TeamEvolutionService.Evaluation>>();String cid=ids.iterator().next();
            for(int i=0;i<4;i++) evaluations.add(pool.submit(()->service.enroll(root.id(),enrollment("same",cid),"owner")));
            var evalIds=new HashSet<String>();for(var future:evaluations)evalIds.add(future.get(20,TimeUnit.SECONDS).id());
            assertThat(evalIds).hasSize(1);assertThat(count("team_evolution_slots")).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void candidateLimitCannotBeBypassedByConcurrentDifferentKeys() throws Exception {
        var r=request("candidate-cap");r.put("maxCandidates",2);var root=service.submit(r,"owner");var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.propose(root.id(),proposal("one",root.baselineId(),"one"),"owner"));
            var b=pool.submit(()->service.propose(root.id(),proposal("two",root.baselineId(),"two"),"owner"));
            assertThat(List.of(a.get(20,TimeUnit.SECONDS).disposition(),b.get(20,TimeUnit.SECONDS).disposition())).containsExactlyInAnyOrder("ACCEPTED","REJECTED");
            assertThat(service.get(root.id()).attempts()).isEqualTo(2);assertThat(count("team_evolution_candidates")).isEqualTo(2);
        } finally {pool.shutdownNow();}
    }
    @Test void validationEnrollmentCannotUseTrainHoldoutForgedMetricsOrOldWorkflow() {
        var root=service.submit(request("no-forged"),"owner");
        for(String split:List.of("TRAIN","HOLDOUT")) {var e=enrollment("bad-"+split,root.baselineId());e.put("split",split);assertThatThrownBy(()->service.enroll(root.id(),e,"owner")).hasMessage("TEAM_ONLY_VALIDATION_ENROLLMENT");}
        for(String field:List.of("workflowRunKey","score","usageSource","inputTokens","traceId")) {var e=enrollment("bad",root.baselineId());e.put(field,"forged");assertThatThrownBy(()->service.enroll(root.id(),e,"owner")).hasMessage("TEAM_FIELDS_REJECTED");}
        assertThat(count("team_evolution_evaluations")).isZero(); assertThat(count("team_evolution_slots")).isZero();
    }
    @Test void dataOverlapAndUnapprovedRoleBindingRejectEntireTransaction() {
        var r=request("overlap");((ObjectNode)r.path("samples").get(1)).put("input"," public　TRAIN ");
        assertThatThrownBy(()->service.submit(r,"owner")).hasMessage("TEAM_SAMPLE_OVERLAP");
        var bad=request("identity");((ObjectNode)bad.path("roles").get(1)).put("instanceKey",firstInstance);
        assertThatThrownBy(()->service.submit(bad,"owner")).hasMessage("TEAM_ROLE_IDENTITY_DUPLICATED");
        assertThat(count("team_evolution_contracts")).isZero();
    }
    @Test void singleRoleGenerationByteAndDuplicateContentRestrictionsPersist() {
        var r=request("bounds");r.put("maxGeneration",1);var root=service.submit(r,"owner");
        assertThat(service.propose(root.id(),proposal("zero",root.baselineId(),"extractor baseline"),"owner").reasonCode()).isEqualTo("TEAM_SINGLE_ROLE_CHANGE_REQUIRED");
        var tooLarge=proposal("bytes",root.baselineId(),"中".repeat(2667));
        assertThat(service.propose(root.id(),tooLarge,"owner").reasonCode()).isEqualTo("TEAM_TEXT_REJECTED");
        var good=service.propose(root.id(),proposal("good",root.baselineId(),"new"),"owner");
        assertThat(service.propose(root.id(),proposal("duplicate",root.baselineId(),"new"),"owner").reasonCode()).isEqualTo("TEAM_CANDIDATE_ALREADY_REGISTERED");
        assertThat(service.propose(root.id(),proposal("generation",good.candidateId(),"newer"),"owner").reasonCode()).isEqualTo("TEAM_GENERATION_LIMIT_REACHED");
    }
    @Test void caseAliasesCannotDuplicateRolesSamplesOrCrossSplitGroups() {
        var roles=request("case-roles");((ObjectNode)roles.path("roles").get(1)).put("role","EXTRACTOR");
        assertThatThrownBy(()->service.submit(roles,"owner")).hasMessage("TEAM_ROLE_IDENTITY_DUPLICATED");
        var ids=request("case-samples");((ObjectNode)ids.path("samples").get(1)).put("id","train-1");
        assertThatThrownBy(()->service.submit(ids,"owner")).hasMessage("TEAM_SAMPLE_OVERLAP");
        var groups=request("case-groups");((ObjectNode)groups.path("samples").get(1)).put("groupId","train-G");
        assertThatThrownBy(()->service.submit(groups,"owner")).hasMessage("TEAM_SAMPLE_OVERLAP");
        assertThat(count("team_evolution_contracts")).isZero();
    }
    @Test void originalKeysAndCandidateReferencesHaveConsistentAsciiCaseSemantics() {
        var root=service.submit(request("Case-Root"),"owner");
        assertThatThrownBy(()->service.submit(request("case-root"),"owner")).isInstanceOf(ConflictException.class);
        var candidate=service.propose(root.id(),proposal("Case-Attempt",root.baselineId().toUpperCase(Locale.ROOT),"new"),"owner");
        assertThat(candidate.disposition()).isEqualTo("ACCEPTED");
        assertThat(service.get(root.id()).candidates()).filteredOn(c->c.id().equals(candidate.candidateId()))
                .extracting(TeamEvolutionService.Candidate::parentId).containsExactly(root.baselineId());
        assertThatThrownBy(()->service.propose(root.id(),proposal("case-attempt",root.baselineId().toUpperCase(Locale.ROOT),"new"),"owner")).isInstanceOf(ConflictException.class);
        var evaluation=service.enroll(root.id(),enrollment("Case-Evaluation",candidate.candidateId().toUpperCase(Locale.ROOT)),"owner");
        assertThat(evaluation.candidateId()).isEqualTo(candidate.candidateId());
        assertThat(service.evaluation(root.id(),evaluation.id().toUpperCase(Locale.ROOT))).isEqualTo(evaluation);
        assertThatThrownBy(()->service.enroll(root.id(),enrollment("case-evaluation",candidate.candidateId().toUpperCase(Locale.ROOT)),"owner")).isInstanceOf(ConflictException.class);
        assertThat(count("team_evolution_contracts")).isEqualTo(1);assertThat(count("team_evolution_attempts")).isEqualTo(1);
        assertThat(count("team_evolution_evaluations")).isEqualTo(1);assertThat(count("team_evolution_slots")).isEqualTo(2);
    }
    @Test void alteredAttemptAndEvaluationConflictWithoutNewRows() {
        var root=service.submit(request("conflicts"),"owner");service.propose(root.id(),proposal("same",root.baselineId(),"new"),"owner");
        assertThatThrownBy(()->service.propose(root.id(),proposal("same",root.baselineId(),"changed"),"owner")).isInstanceOf(ConflictException.class);
        service.enroll(root.id(),enrollment("same",root.baselineId()),"owner");var other=enrollment("same",root.baselineId());other.put("split","HOLDOUT");
        assertThatThrownBy(()->service.enroll(root.id(),other,"owner")).isInstanceOf(ConflictException.class);
        assertThat(count("team_evolution_attempts")).isEqualTo(1);assertThat(count("team_evolution_evaluations")).isEqualTo(1);
    }
    @Test void escapedRolePromptsRoundTripBeyondTheMysqlTextByteLimit() {
        var root=service.submit(request("escaped-prompts"),"owner");
        String expanded="A"+String.valueOf((char)0).repeat(7998)+"B";
        var first=service.propose(root.id(),proposal("first-role",root.baselineId(),expanded),"owner");
        var next=proposal("second-role",first.candidateId(),expanded);
        ((ObjectNode)next.path("configuration").path("rolePrompts")).put("classifier",expanded);
        var second=service.propose(root.id(),next,"owner");assertThat(second.disposition()).isEqualTo("ACCEPTED");
        String saved=db.queryForObject("select configuration_json from team_evolution_candidates where contract_id=? and candidate_id=?",String.class,root.id(),second.candidateId());
        assertThat(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(65535);
        assertThat(json.valueToTree(service.content(root.id())).path("candidates").findValuesAsText("extractor")).contains(expanded);
    }
    @Test void disabledContractsRejectWritesButStillReadOriginalMetadata() {
        var root=service.submit(request("disabled"),"owner");
        var disabled=new TeamEvolutionService(db,json,agents,runtimes,connections);
        assertThat(disabled.status().get("executionAvailable")).isEqualTo(false);
        assertThatThrownBy(()->disabled.submit(request("new-disabled"),"owner")).hasMessage("TEAM_CONTRACTS_DISABLED");
        assertThat(disabled.get(root.id())).isEqualTo(root);
    }
    @Test void administratorHttpFlowPersistsOriginalPrincipalAndStableSlots() throws Exception {
        var auth=auth("PLATFORM_ADMIN","http-owner");
        var created=mvc.perform(post("/api/team-evolutions").with(auth).contentType("application/json").content(json.writeValueAsString(request("http-create"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode root=json.readTree(created.getResponse().getContentAsString()).path("data");long id=root.path("id").asLong();
        var proposed=mvc.perform(post("/api/team-evolutions/"+id+"/candidates").with(auth).contentType("application/json")
                .content(json.writeValueAsString(proposal("http-proposal",root.path("baselineId").asText(),"new via HTTP"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.disposition").value("ACCEPTED")).andReturn();
        String candidate=json.readTree(proposed.getResponse().getContentAsString()).path("data").path("candidateId").asText();
        String body=json.writeValueAsString(enrollment("http-eval",candidate));
        var enrolled=mvc.perform(post("/api/team-evolutions/"+id+"/evaluations").with(auth).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("NOT_DISPATCHED")).andReturn();
        JsonNode expected=json.readTree(enrolled.getResponse().getContentAsString()).path("data");
        var replay=mvc.perform(post("/api/team-evolutions/"+id+"/evaluations").with(auth).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("data")).isEqualTo(expected);
        mvc.perform(get("/api/team-evolutions/"+id+"/evaluations/"+expected.path("id").asText()).with(auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slots.length()").value(2));
        assertThat(count("team_evolution_contracts")).isEqualTo(1);assertThat(count("team_evolution_evaluations")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void centralizedRoleCapabilitiesProtectContentAndAllWrites(String role) throws Exception {
        var root=service.submit(request("roles"),"owner");var auth=auth(role,"owner");
        mvc.perform(get("/api/team-evolutions/"+root.id()).with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.executionState").value("DISABLED_NO_EXECUTOR"));
        mvc.perform(get("/api/team-evolutions/status").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.executionAvailable").value(false));
        int content=Set.of("GOVERNANCE_ADMIN","PLATFORM_ADMIN").contains(role)?200:403;
        mvc.perform(get("/api/team-evolutions/"+root.id()+"/content").with(auth)).andExpect(status().is(content));
        for(String path:List.of("/api/team-evolutions","/api/team-evolutions/"+root.id()+"/candidates","/api/team-evolutions/"+root.id()+"/evaluations")) {
            mvc.perform(post(path).with(auth).contentType("application/json").content("{}"))
                    .andExpect(status().is(role.equals("PLATFORM_ADMIN")?400:403));
        }
        assertThat(count("team_evolution_attempts")).isZero();assertThat(count("team_evolution_evaluations")).isZero();
    }
    @Test void anonymousAndNonConsoleRolesCannotReadOrWriteContracts() throws Exception {
        var root=service.submit(request("denied"),"owner");
        mvc.perform(get("/api/team-evolutions/"+root.id())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/team-evolutions").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        for(String role:List.of("DATA_OWNER","NONE")) mvc.perform(get("/api/team-evolutions/"+root.id()).with(auth(role,"owner"))).andExpect(status().isForbidden());
    }

    @Test void consoleReadsAreMetadataOnlyAndNeverEnrollOrChangeUnknown() throws Exception {
        var root=service.submit(request("console-read"),"owner");
        var evaluation=service.enroll(root.id(),enrollment("enroll",root.baselineId()),"owner");
        db.update("update team_evolution_slots set state='UNKNOWN' where evaluation_id=? and role_ordinal=0",evaluation.id());
        var before=db.queryForList("select * from team_evolution_slots where evaluation_id=? order by role_ordinal",evaluation.id());
        var detail=console.get(root.id());
        assertThat(detail.summary().id()).isEqualTo(root.id()); assertThat(detail.budget()).isNull();
        assertThat(detail.holdout()).isNull(); assertThat(detail.closure()).isNull();
        assertThat(detail.evaluations().getFirst().execution()).isNull(); assertThat(detail.evaluations().getFirst().score()).isNull();
        assertThat(detail.evaluations().getFirst().registration().slots()).anyMatch(s->s.state().equals("UNKNOWN"));
        assertThat(detail.sampleCounts()).containsEntry("HOLDOUT",1);
        String encoded=json.writeValueAsString(detail);
        assertThat(encoded).doesNotContain("public TRAIN","answer HOLDOUT","extractor baseline","substrate://","principalDigest","instanceKey","actorUid","dataset_json","rolePrompts");
        console.list(null); console.get(root.id());
        assertThat(db.queryForList("select * from team_evolution_slots where evaluation_id=? order by role_ordinal",evaluation.id())).isEqualTo(before);
        assertThat(count("team_evolution_evaluations")).isEqualTo(1); assertThat(count("team_evolution_executions")).isZero(); assertThat(count("team_evolution_scores")).isZero(); assertThat(count("team_evolution_closures")).isZero();
    }

    @Test void consoleUsesStableCursorWithoutRepeatingNewerRecords() {
        var root=service.submit(request("console-page"),"owner");
        for(int i=0;i<21;i++) cloneConsoleRoot(root.id(),"copy-"+i,null);
        var firstPage=console.list(null); assertThat(firstPage.items()).hasSize(20); assertThat(firstPage.nextBeforeId()).isNotNull();
        assertThat(firstPage.items()).extracting(TeamEvolutionConsoleService.Row::id).isSortedAccordingTo(Comparator.reverseOrder());
        cloneConsoleRoot(root.id(),"newer",null);
        var secondPage=console.list(firstPage.nextBeforeId()); assertThat(secondPage.items()).hasSize(2); assertThat(secondPage.nextBeforeId()).isNull();
        assertThat(secondPage.items()).noneMatch(r->firstPage.items().stream().anyMatch(f->f.id()==r.id()));
        assertThatThrownBy(()->console.list(0L)).hasMessage("TEAM_CONSOLE_CURSOR_REJECTED");
    }

    @Test void consoleScanCapAllowsEmptyVisiblePageAndContinuesPastHiddenRoots() {
        var root=service.submit(request("console-scan"),"owner");
        long hidden=fixture("hidden"); db.update("update agents set record_origin='DEMO' where id=?",hidden);
        var protocol=(ObjectNode)json.valueToTree(service.content(root.id()).get("protocol"));
        ((ObjectNode)protocol.path("roles").get(0)).put("agentId",hidden);
        for(int i=0;i<201;i++) cloneConsoleRoot(root.id(),"hidden-"+i,protocol.toString());
        var firstPage=console.list(null); assertThat(firstPage.items()).isEmpty(); assertThat(firstPage.scanLimited()).isTrue(); assertThat(firstPage.nextBeforeId()).isNotNull();
        var secondPage=console.list(firstPage.nextBeforeId()); assertThat(secondPage.items()).extracting(TeamEvolutionConsoleService.Row::id).containsExactly(root.id()); assertThat(secondPage.nextBeforeId()).isNull();
        long hiddenRoot=db.queryForObject("select max(id) from team_evolution_contracts",Long.class);
        assertThatThrownBy(()->console.get(hiddenRoot)).hasMessage("智能体不存在");
    }

    @Test void consoleUnreadableRecordsAreErrorsRatherThanEmptySuccessfulReports() {
        var root=service.submit(request("console-corrupt"),"owner");
        db.update("update team_evolution_contracts set protocol_json='{}' where id=?",root.id());
        assertThatThrownBy(()->console.list(null)).hasMessage("TEAM_RECORD_UNREADABLE");
    }

    @Test void consoleRetainsOriginalClosureAndDistinguishesMissingHoldout() {
        var root=service.submit(request("console-closure"),"owner");
        var costs=new TeamEvolutionScoringService.Costs(0,0,0,0,0,0,0,false,0);
        var closure=new TeamEvolutionHoldoutService.Closure(root.protocolDigest(),root.datasetDigest(),null,null,root.baselineId(),null,"INCOMPLETE_NO_ADOPTION","HOLDOUT_QUALITY_THEN_PARETO_V1",false,false,false,null,null,costs,0,0,1,List.of());
        db.update("insert into team_evolution_closures(contract_id,request_key,fingerprint,principal_digest,result_json,created_at) values(?,'close',?,?,?,CURRENT_TIMESTAMP)",root.id(),"a".repeat(64),"b".repeat(64),service.encode(closure));
        var before=db.queryForObject("select result_json from team_evolution_closures where contract_id=?",String.class,root.id());
        assertThat(console.get(root.id()).closure()).isEqualTo(closure); assertThat(console.get(root.id()).holdout()).isNull();
        assertThat(console.list(null).items().getFirst().recommendation()).isEqualTo("INCOMPLETE_NO_ADOPTION");
        assertThat(db.queryForObject("select result_json from team_evolution_closures where contract_id=?",String.class,root.id())).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void consoleEndpointsUseCentralReadPermissionAndRejectEveryWrite(String role) throws Exception {
        var root=service.submit(request("console-roles"),"owner"); var auth=auth(role,"owner");
        for(String path:List.of("/api/team-evolutions/console","/api/team-evolutions/"+root.id()+"/console")) {
            var result=mvc.perform(get(path).with(auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(result).doesNotContain("extractor baseline","public TRAIN","answer HOLDOUT","substrate://");
            mvc.perform(post(path).with(auth).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(put(path).with(auth).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(delete(path).with(auth)).andExpect(status().isForbidden());
            mvc.perform(get(path+"/content").with(auth)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/team-evolutions/console?beforeId=-1").with(auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/team-evolutions/console?beforeId=bad").with(auth)).andExpect(status().isBadRequest());
    }

    @Test void consoleAnonymousAndNonConsoleCannotReadEitherProjection() throws Exception {
        var root=service.submit(request("console-denied"),"owner");
        for(String path:List.of("/api/team-evolutions/console","/api/team-evolutions/"+root.id()+"/console")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(auth("DATA_OWNER","owner"))).andExpect(status().isForbidden());
        }
    }

    void cloneConsoleRoot(long original,String key,String protocol) {
        db.update("insert into team_evolution_contracts(submission_key,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,protocol_json,dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,attempts,created_at) select ?,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,coalesce(?,protocol_json),dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,attempts,created_at from team_evolution_contracts where id=?",key,protocol,original);
    }
    @Test void submissionReceiptIsSubjectScopedReadOnlyAndHasNoContent() {
        var root=service.submit(request("receipt-create"),"owner");
        var found=operations.receipt(TeamEvolutionOperationService.Action.CREATE,"RECEIPT-CREATE",null,null,"owner");
        assertThat(found.state()).isEqualTo("FOUND");assertThat(found.rootId()).isEqualTo(root.id());assertThat(found.result()).isEqualTo(root);
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.CREATE,"receipt-create",null,null,"other").state()).isEqualTo("NOT_FOUND");
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.CREATE,"absent",null,null,"owner").result()).isNull();
        assertThat(count("team_evolution_contracts")).isEqualTo(1);
        assertThat(service.encode(found)).doesNotContain("public TRAIN","answer HOLDOUT","extractor baseline","principalDigest","instanceKey","endpoint");
    }
    @Test void candidateReceiptsPreserveBothAcceptedAndDurablyRejectedAttempts() {
        var root=service.submit(request("receipt-proposal"),"owner");
        var accepted=service.propose(root.id(),proposal("accepted",root.baselineId(),"changed prompt"),"owner");
        var rejected=service.propose(root.id(),proposal("rejected",root.baselineId(),"extractor baseline"),"owner");
        assertThat(rejected.disposition()).isEqualTo("REJECTED");
        for(String key:List.of("accepted","rejected")) assertThat(operations.receipt(TeamEvolutionOperationService.Action.CANDIDATE,key,root.id(),null,"owner").result()).isEqualTo(key.equals("accepted")?accepted:rejected);
        assertThat(count("team_evolution_attempts")).isEqualTo(2);assertThat(service.get(root.id()).attempts()).isEqualTo(2);
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.CANDIDATE,"absent",root.id(),null,"owner").state()).isEqualTo("NOT_FOUND");
        assertThatThrownBy(()->operations.receipt(TeamEvolutionOperationService.Action.CANDIDATE,"accepted",root.id(),null,"other")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
    }
    @Test void enrollmentReceiptUsesOriginalRootAndNeverCreatesSlots() {
        var root=service.submit(request("receipt-enroll"),"owner");var e=service.enroll(root.id(),enrollment("enrolled",root.baselineId()),"owner");
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.ENROLL,"enrolled",root.id(),null,"owner").result()).isEqualTo(e);
        var other=service.submit(request("receipt-other"),"owner");
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.ENROLL,"enrolled",other.id(),null,"owner").state()).isEqualTo("NOT_FOUND");
        assertThatThrownBy(()->operations.receipt(TeamEvolutionOperationService.Action.EXECUTE,"execution",other.id(),e.id(),"owner")).hasMessage("TEAM_EVALUATION_NOT_FOUND");
        assertThat(count("team_evolution_evaluations")).isEqualTo(1);assertThat(count("team_evolution_slots")).isEqualTo(2);
    }
    @Test void absentOperationReceiptsDoNotInventSuccessOrAcquireResponsibility() {
        var root=service.submit(request("receipt-absent"),"owner");var e=service.enroll(root.id(),enrollment("enrolled",root.baselineId()),"owner");
        for(var action:TeamEvolutionOperationService.Action.values()) {
            boolean create=action==TeamEvolutionOperationService.Action.CREATE,batch=action==TeamEvolutionOperationService.Action.EXECUTE||action==TeamEvolutionOperationService.Action.SCORE;
            var receipt=operations.receipt(action,"not-sent",create?null:root.id(),batch?e.id():null,"owner");
            assertThat(receipt.state()).isEqualTo("NOT_FOUND");assertThat(receipt.result()).isNull();
        }
        for(String table:List.of("team_evolution_attempts","team_evolution_budget_plans","team_evolution_executions","team_evolution_scores","team_evolution_frontiers","team_evolution_holdouts","team_evolution_closures"))assertThat(count(table)).isZero();
    }
    @Test void receiptReadPreservesExpiredRunningAndUnknownOriginalExecution() {
        var root=service.submit(request("receipt-running"),"owner");var e=service.enroll(root.id(),enrollment("enrolled",root.baselineId()),"owner");
        db.update("insert into team_evolution_executions(evaluation_id,request_key,fingerprint,principal_digest,workflow_key,holder,deadline,state,started_at) values(?,'original',?,?,?,?,'2020-01-01 00:00:00','RUNNING','2019-12-31 23:59:00')",e.id(),"a".repeat(64),service.principal("owner"),"receipt:"+e.id(),UUID.randomUUID().toString());
        for(String state:List.of("RUNNING","UNKNOWN")) {
            db.update("update team_evolution_executions set state=? where evaluation_id=?",state,e.id());
            var result=(TeamEvolutionExecutionService.Result)operations.receipt(TeamEvolutionOperationService.Action.EXECUTE,"original",root.id(),e.id(),"owner").result();
            assertThat(result.state()).isEqualTo(state);assertThat(db.queryForObject("select state from team_evolution_executions where evaluation_id=?",String.class,e.id())).isEqualTo(state);
        }
        assertThat(count("team_evolution_dispatches")).isZero();
    }
    @Test void operationContextUsesOriginalSubjectWithoutReturningProtocolIdentity() {
        var root=service.submit(request("operation-context"),"owner");
        assertThat(operations.context(root.id(),"owner").originalSubmitter()).isTrue();assertThat(operations.context(root.id(),"other").originalSubmitter()).isFalse();
        assertThat(operations.context(root.id(),"owner").maxTokens()).isEqualTo(128);
        assertThat(service.encode(operations.context(root.id(),"owner"))).doesNotContain("extractor baseline","principalDigest","endpoint","instanceKey");
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void operationContextAndReceiptsUseCentralExperimentCapability(String role) throws Exception {
        var root=service.submit(request("receipt-permissions"),"owner");int expected=role.equals("PLATFORM_ADMIN")?200:403;
        String path="/api/team-evolutions/receipts?action=CREATE&requestKey=receipt-permissions";
        mvc.perform(get(path).with(auth(role,"owner"))).andExpect(status().is(expected));
        mvc.perform(get("/api/team-evolutions/"+root.id()+"/actions").with(auth(role,"owner"))).andExpect(status().is(expected));
        for(String route:List.of("/api/team-evolutions/receipts","/api/team-evolutions/"+root.id()+"/actions")) {
            mvc.perform(post(route).with(auth(role,"owner")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(put(route).with(auth(role,"owner")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
            mvc.perform(delete(route).with(auth(role,"owner"))).andExpect(status().isForbidden());
            mvc.perform(get(route+"/content").with(auth(role,"owner"))).andExpect(status().isForbidden());
        }
    }
    @Test void receiptQueriesRejectAmbiguousShapeAndAnonymousAccess() throws Exception {
        var root=service.submit(request("receipt-validation"),"owner");
        String path="/api/team-evolutions/receipts";var admin=auth("PLATFORM_ADMIN","owner");
        mvc.perform(get(path+"?action=CREATE&requestKey=x")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"?action=CREATE&requestKey=x").with(auth("DATA_OWNER","owner"))).andExpect(status().isForbidden());
        for(String query:List.of("action=BAD&requestKey=x","action=CREATE&requestKey=x&rootId="+root.id(),"action=ENROLL&requestKey=x","action=EXECUTE&requestKey=x&rootId="+root.id(),"action=CANDIDATE&requestKey=x&rootId="+root.id()+"&evaluationId=unexpected","action=CREATE&requestKey="+"a".repeat(65)))mvc.perform(get(path+"?"+query).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(path+"?action=CREATE&requestKey=receipt-validation").with(auth("PLATFORM_ADMIN","other"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("NOT_FOUND"));
    }

}
