package com.eap.platform.learning;
import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class LearningFeedbackIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired LearningFeedbackService service;
    @Autowired LearningFeedbackRepository repo;
    @Autowired AgentService agents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    Long agentId;
    @BeforeEach void setup(){agentId=agents.create(new AgentDtos.AgentRequest("FDB-"+UUID.randomUUID(),"Feedback test","","Test","Owner","Test",RiskLevel.LOW)).id();}
    LearningFeedbackDtos.FeedbackRequest request(String key,Long task,String trace){return new LearningFeedbackDtos.FeedbackRequest(agentId,"QUALITY",3,"observed quality gap","candidate suggestion",task,trace,key);}
    org.springframework.test.web.servlet.request.RequestPostProcessor role(String name){return jwt().jwt(j->j.subject("actor-"+name)).authorities(new SimpleGrantedAuthority("ROLE_"+name));}
    @ParameterizedTest @CsvSource({"VIEWER,false,false","OPERATOR,true,false","GOVERNANCE_ADMIN,true,true","PLATFORM_ADMIN,true,true"})
    void roleCapabilitiesAndContentAreConsistent(String role,boolean write,boolean content)throws Exception {
        mvc.perform(get("/api/platform/me").with(role(role))).andExpect(jsonPath("$.data.capabilities.manageFeedback").value(write));
        var result=mvc.perform(post("/api/learning-feedbacks").with(role(role)).contentType("application/json").content(mapper.writeValueAsBytes(request(UUID.randomUUID().toString(),null,null))));
        if(!write){result.andExpect(status().isForbidden());return;}
        result.andExpect(status().isOk()).andExpect(jsonPath("$.data.requestedBy").value("actor-"+role)).andExpect(jsonPath("$.data.contentVisible").value(content));
        if(content)result.andExpect(jsonPath("$.data.summary").value("observed quality gap"));else result.andExpect(jsonPath("$.data.summary").doesNotExist()).andExpect(jsonPath("$.data.improvementAction").doesNotExist());
        mvc.perform(get("/api/learning-feedbacks?agentId="+agentId).with(role(role))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].contentVisible").value(content));
    }
    @Test void productionFeedbackExcludesDemoSeedsAndCannotReviewOrCreateThem()throws Exception {
        assertThat(service.list(null,100,true)).noneMatch(f->f.agentId()<=3);
        assertThatThrownBy(()->service.create(new LearningFeedbackDtos.FeedbackRequest(1L,"QUALITY",3,"demo","",null,null,"demo-reject-1"),"test",true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.evidence(1L)).isInstanceOf(BusinessException.class);
    }
    @Test void anonymousCannotReadOrWrite()throws Exception {
        mvc.perform(get("/api/learning-feedbacks")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/learning-feedbacks").contentType("application/json").content(mapper.writeValueAsBytes(request("anonymous-1",null,null)))).andExpect(status().isUnauthorized());
    }
    @Test void rejectsAbsentIdempotencyAndWhitespaceSummary()throws Exception {
        mvc.perform(post("/api/learning-feedbacks").with(role("OPERATOR")).contentType("application/json").content("{\"agentId\":"+agentId+",\"feedbackType\":\"QUALITY\",\"rating\":3,\"summary\":\" \"}")).andExpect(status().isBadRequest());
        assertThat(service.list(agentId,100,true)).isEmpty();
    }
    @Test void concurrentSameSubmissionCreatesOneRecordAndDifferentPayloadConflicts()throws Exception {
        String key=UUID.randomUUID().toString();var r=request(key,null,null);var pool=Executors.newFixedThreadPool(8);
        try{var start=new CountDownLatch(1);List<Future<Long>> futures=new ArrayList<>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{start.await();return service.create(r,"same-user",true).id();}));start.countDown();Set<Long> ids=new HashSet<>();for(var f:futures)ids.add(f.get(20,TimeUnit.SECONDS));assertThat(ids).hasSize(1);assertThat(service.list(agentId,100,true)).hasSize(1);}
        finally{pool.shutdownNow();}
        assertThatThrownBy(()->service.create(new LearningFeedbackDtos.FeedbackRequest(agentId,"QUALITY",1,"different","",null,null,key),"same-user",true)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->service.create(r,"different-user",true)).isInstanceOf(ConflictException.class);
    }
    @Test void taskAndTraceMustBelongToChosenAgentAndEvidenceDoesNotExposeBodies()throws Exception {
        Long other=agents.create(new AgentDtos.AgentRequest("FDB-other-"+UUID.randomUUID(),"Other","","Test","Owner","Test",RiskLevel.LOW)).id();
        jdbc.update("insert into agent_tasks(agent_id,task_type,input_summary,status,created_at,updated_at) values(?,?,'private task body','SUCCEEDED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",other,"TEST");
        Long task=jdbc.queryForObject("select max(id) from agent_tasks where agent_id=?",Long.class,other);
        String trace="fdb-trace-"+UUID.randomUUID();
        jdbc.update("insert into trace_span_observations(trace_id,span_id,endpoint_key,agent_id,span_name,span_kind,duration_ms,observed_at,created_at,attributes_json) values(?,?,?,?,'private span name','AGENT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'private attributes')",trace,"s1","fdb-endpoint",other);
        assertThatThrownBy(()->service.create(request("bad-task-1",task,null),"test",true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create(request("bad-trace-1",null,trace),"test",true)).isInstanceOf(BusinessException.class);
        assertThat(service.list(agentId,100,true)).isEmpty();
        var accepted=service.create(new LearningFeedbackDtos.FeedbackRequest(other,"TOOL",2,"gap","fix",task,trace,"good-evidence-1"),"test",true);
        assertThat(accepted.taskId()).isEqualTo(task);assertThat(accepted.traceId()).isEqualTo(trace);
        mvc.perform(get("/api/learning-feedbacks/evidence?agentId="+other).with(role("VIEWER"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.tasks[0].id").value(task)).andExpect(jsonPath("$.data.tasks[0].inputSummary").doesNotExist()).andExpect(jsonPath("$.data.traces[0]").value(trace));
    }
    @Test void statusRequiresContentPermissionAndCurrentStateAndKeepsAdoptionSeparate()throws Exception {
        Long id=service.create(request("review-test-1",null,null),"author",true).id();
        mvc.perform(post("/api/learning-feedbacks/"+id+"/status").with(role("OPERATOR")).contentType("application/json").content("{\"status\":\"REVIEWING\",\"expectedStatus\":\"OPEN\"}")).andExpect(status().isForbidden());
        var before=jdbc.queryForObject("select count(*) from substrate_agent_deployments",Long.class);
        service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.REVIEWING,LearningFeedbackStatus.OPEN),"reviewer",true);
        assertThatThrownBy(()->service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.ADOPTED,LearningFeedbackStatus.OPEN),"reviewer",true)).isInstanceOf(ConflictException.class);
        var adopted=service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.ADOPTED,LearningFeedbackStatus.REVIEWING),"reviewer",true);
        assertThat(adopted.reviewedBy()).isEqualTo("reviewer");assertThat(adopted.status()).isEqualTo(LearningFeedbackStatus.ADOPTED);
        assertThat(jdbc.queryForObject("select count(*) from substrate_agent_deployments",Long.class)).isEqualTo(before);
        assertThatThrownBy(()->service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.OPEN,LearningFeedbackStatus.ADOPTED),"reviewer",true)).isInstanceOf(BusinessException.class);
    }
    @Test void statusHttpConflictIs409AndReplaysAreSafe()throws Exception {
        var r=request("http-replay-1",null,null);String body=mapper.writeValueAsString(r);
        String first=mvc.perform(post("/api/learning-feedbacks").with(role("PLATFORM_ADMIN")).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Long id=mapper.readTree(first).path("data").path("id").asLong();
        mvc.perform(post("/api/learning-feedbacks").with(role("PLATFORM_ADMIN")).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.REVIEWING,LearningFeedbackStatus.OPEN),"first-reviewer",true);
        mvc.perform(post("/api/learning-feedbacks/"+id+"/status").with(role("GOVERNANCE_ADMIN")).contentType("application/json").content("{\"status\":\"ADOPTED\",\"expectedStatus\":\"OPEN\"}")).andExpect(status().isConflict());
        assertThat(service.updateStatus(id,new LearningFeedbackDtos.StatusRequest(LearningFeedbackStatus.REVIEWING,LearningFeedbackStatus.OPEN),"second-reviewer",true).reviewedBy()).isEqualTo("first-reviewer");
    }
}
