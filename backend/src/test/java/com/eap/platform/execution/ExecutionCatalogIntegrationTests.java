package com.eap.platform.execution;

import com.eap.platform.agent.*;
import com.eap.platform.workflow.WorkflowExecution;
import com.eap.platform.ax.AxTask;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class ExecutionCatalogIntegrationTests {
    @Autowired ExecutionCatalogService service;
    @Autowired JdbcTemplate db;
    @Autowired AgentRepository agents;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entities;
    long agent, workflow;
    String prefix;
    final Instant time = Instant.parse("2026-01-01T00:00:00Z");
    Authentication auth(String user, String role) { return new UsernamePasswordAuthenticationToken(user, "", List.of(new SimpleGrantedAuthority("ROLE_"+role))); }
    @BeforeEach void fixture() {
        prefix = "catalog-"+UUID.randomUUID();
        var a = new Agent(); a.setCode(prefix); a.setName(prefix); a.setOwnerDepartment("test"); a.setOwnerUser("test");
        a.setCategory("test"); a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE); a.setRiskLevel(RiskLevel.LOW);
        agent = agents.saveAndFlush(a).getId();
        db.update("insert into workflow_definitions(code,name,status,created_at,updated_at) values(?,?,'DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",prefix,prefix);
        workflow = db.queryForObject("select id from workflow_definitions where code=?",Long.class,prefix);
    }
    void persist(Object entity, Object... fields) {
        for (int i=0;i<fields.length;i+=2) ReflectionTestUtils.setField(entity,(String)fields[i],fields[i+1]);
        entities.persist(entity);entities.flush();
    }
    String workflow(int n, String status, String owner) { return workflow(n,status,owner,time); }
    String workflow(int n, String status, String owner, Instant created) {
        String key=prefix+"-"+String.format("%03d",n);
        persist(new WorkflowExecution(),"workflowId",workflow,"runKey",key,"status",status,"objective",prefix,
                "requester",owner,"snapshotJson","PRIVATE_SNAPSHOT","createdAt",created);
        return key;
    }
    String ax(boolean nested) { return ax(nested,time); }
    String ax(boolean nested, Instant created) {
        String key=UUID.randomUUID().toString();
        persist(new AxTask(),"taskKey",key,"submissionKey",key,"agentId",agent,"fingerprint","digest",
                "requestedBy","alice","status","SUCCEEDED","phase","DONE","reason","safe",
                "payloadJson","PRIVATE_INPUT","createdAt",created,"updatedAt",created,"deadlineAt",created,
                "workflowId",nested?workflow:null);
        return key;
    }
    String chat(String owner, String status) throws Exception { return chat(owner,status,time); }
    String chat(String owner, String status, Instant created) throws Exception {
        String key=UUID.randomUUID().toString();
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8)));
        db.update("insert into eap_chat_submissions(owner_hash,request_key,agent_id,instance_name,payload_digest,status,created_at,dispatch_deadline,updated_at) values(?,?,?,'test-instance','PRIVATE_DIGEST',?,?,?,?)",hash,key,agent,status,Timestamp.from(created),Timestamp.from(created),Timestamp.from(created));
        return key;
    }
    ExecutionCatalogService.Page list(String kind,String status,String query,boolean mine,int page,int size,Authentication auth) {
        return service.list(kind,status,query,mine,null,null,null,page,size,auth);
    }
    @Test void pagesAllHistoryWithDeterministicTieOrderingWithoutReadingPayloads() throws Exception {
        for(int n=0;n<43;n++) workflow(n,"SUCCEEDED","alice");
        var first=list("ALL","ALL",prefix,false,1,20,auth("alice","VIEWER"));
        var second=list("ALL","ALL",prefix,false,2,20,auth("alice","VIEWER"));
        var third=list("ALL","ALL",prefix,false,3,20,auth("alice","VIEWER"));
        assertThat(first.total()).isEqualTo(43); assertThat(third.items()).hasSize(3);
        var keys=new HashSet<String>(); for(var p:List.of(first,second,third)) for(var e:p.items()) assertThat(keys.add(e.runKey())).isTrue();
        assertThat(first.items().getFirst().runKey()).endsWith("042");
        assertThat(mapper.writeValueAsString(first)).doesNotContain("PRIVATE_");
    }
    @Test void filtersDateStatusRequesterAndTreatsWildcardInputLiterally() {
        workflow(1,"FAILED","alice");workflow(2,"EXECUTION_UNKNOWN","bob");workflow(3,"SUCCEEDED","alice");
        assertThat(list("ALL","ATTENTION",prefix,false,1,20,auth("alice","VIEWER")).total()).isEqualTo(2);
        assertThat(list("ALL","UNKNOWN",prefix,true,1,20,auth("alice","VIEWER")).total()).isZero();
        assertThat(list("ALL","ALL","%_' OR 1=1 --",false,1,20,auth("alice","VIEWER")).total()).isZero();
        assertThat(service.list("ALL","ALL",prefix,false,workflow,time,time.plusSeconds(1),1,20,auth("alice","VIEWER")).total()).isEqualTo(3);
        assertThat(service.list("ALL","ALL",prefix,false,null,time.plusSeconds(1),null,1,20,auth("alice","VIEWER")).total()).isZero();
        assertThat(service.list("ALL","ALL",prefix,false,null,null,time,1,20,auth("alice","VIEWER")).total()).isZero();
    }
    @Test void preservesJpaAndJdbcInstantsWhenMergingFilteringAndPagingSources() throws Exception {
        String w=workflow(1,"SUCCEEDED","alice",time.plusSeconds(1));
        String x=ax(false,time.plusSeconds(2)),c=chat("alice","SUCCEEDED",time.plusSeconds(3));
        var user=auth("alice","OPERATOR");
        var all=list("ALL","ALL",prefix,false,1,20,user);
        assertThat(all.items()).extracting(ExecutionCatalogService.Entry::runKey).containsExactly(c,x,w);
        assertThat(all.items()).extracting(ExecutionCatalogService.Entry::createdAt)
                .containsExactly(time.plusSeconds(3),time.plusSeconds(2),time.plusSeconds(1));
        for(int page=1;page<=3;page++)
            assertThat(list("ALL","ALL",prefix,false,page,1,user).items().getFirst()).isEqualTo(all.items().get(page-1));
        assertThat(service.list("ALL","ALL",prefix,false,null,time.plusSeconds(2),time.plusSeconds(3),1,20,user).items())
                .extracting(ExecutionCatalogService.Entry::runKey).containsExactly(x);
        assertThat(service.list("CHAT","ALL",prefix,false,null,time.plusSeconds(3),time.plusSeconds(4),1,20,user).items())
                .extracting(ExecutionCatalogService.Entry::runKey).containsExactly(c);
        assertThat(all.items()).extracting(ExecutionCatalogService.Entry::requester).containsOnly("alice");
        db.update("update eap_chat_submissions set created_at=? where request_key=?",Timestamp.from(time.minusSeconds(1)),c);
        assertThat(list("ALL","ALL",prefix,false,1,20,user).items())
                .extracting(ExecutionCatalogService.Entry::runKey).containsExactly(x,w,c);
        for(int page=1;page<=3;page++) assertThat(list("ALL","ALL",prefix,false,page,1,user).items().getFirst().runKey())
                .isEqualTo(List.of(x,w,c).get(page-1));
    }
    @Test void includesOnlyIndependentAxTasksAndPreservesOriginalKeys() throws Exception {
        String independent=ax(false),nested=ax(true);
        var result=list("AX","ALL",prefix,false,1,20,auth("alice","VIEWER"));
        assertThat(result.items()).extracting(ExecutionCatalogService.Entry::runKey).containsExactly(independent).doesNotContain(nested);
        assertThat(mapper.writeValueAsString(result)).doesNotContain("PRIVATE_INPUT");
    }
    @Test void identifiesOnlyTheExactPlanExecutionWithoutDuplicatingOtherWorkflowRuns() {
        String key=workflow(1,"SUCCEEDED","alice"); workflow(2,"SUCCEEDED","alice");
        db.update("insert into agent_teams(code,name,coordination_mode,workspace_policy,status,created_at,updated_at) values(?,?,'SEQUENTIAL','ISOLATED','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",prefix,prefix);
        Long team=db.queryForObject("select id from agent_teams where code=?",Long.class,prefix);
        db.update("insert into agent_plans(team_id,goal,status,created_at,updated_at,execution_workflow_id,execution_run_key) values(?,?,'COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)",team,prefix,workflow,key);
        assertThat(list("PLAN","ALL",prefix,false,1,20,auth("alice","VIEWER")).items()).extracting(ExecutionCatalogService.Entry::runKey).containsExactly(key);
        assertThat(list("WORKFLOW","ALL",prefix,false,1,20,auth("alice","VIEWER")).total()).isEqualTo(1);
    }
    @Test void chatsRequireExistingCapabilityAndExactOwnerEvenForAdministrator() throws Exception {
        String alice=chat("alice","SUCCEEDED"),bob=chat("bob","SUCCEEDED");
        for(String role:List.of("VIEWER","GOVERNANCE_ADMIN")) {
            var result=list("ALL","ALL",prefix,false,1,20,auth("alice",role));
            assertThat(result.items()).isEmpty();assertThat(result.ownChatsIncluded()).isFalse();
        }
        for(String role:List.of("OPERATOR","PLATFORM_ADMIN")) {
            var result=list("CHAT","ALL",prefix,false,1,20,auth("alice",role));
            assertThat(result.items()).extracting(ExecutionCatalogService.Entry::runKey).containsExactly(alice).doesNotContain(bob);
            assertThat(mapper.writeValueAsString(result)).doesNotContain("PRIVATE_DIGEST","owner_hash");
        }
    }
    @Test void expiredChatIsUnknownProjectionAndReadNeverMutatesOriginalReceipt() throws Exception {
        String key=chat("alice","DISPATCHED");
        for(int i=0;i<2;i++) {
            var result=list("CHAT","UNKNOWN",prefix,false,1,20,auth("alice","OPERATOR"));
            assertThat(result.items()).singleElement().satisfies(e->{assertThat(e.runKey()).isEqualTo(key);assertThat(e.status()).isEqualTo("UNKNOWN");});
        }
        assertThat(db.queryForObject("select status from eap_chat_submissions where request_key=?",String.class,key)).isEqualTo("DISPATCHED");
    }
    @Test void productionCatalogueExcludesDemoAgentAxAndChat() throws Exception {
        ax(false);chat("alice","SUCCEEDED");
        db.update("update agents set record_origin='DEMO' where id=?",agent);
        assertThat(list("ALL","ALL",prefix,false,1,20,auth("alice","PLATFORM_ADMIN")).items()).isEmpty();
    }
    @Test void rejectsUnboundedAndMalformedFilters() {
        var user=auth("alice","VIEWER");
        assertThatThrownBy(()->list("OTHER","ALL","",false,1,20,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->list("ALL","OTHER","",false,1,20,user)).isInstanceOf(BusinessException.class);
        for(int size:List.of(0,51)) assertThatThrownBy(()->list("ALL","ALL","",false,1,size,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->list("ALL","ALL","x".repeat(129),false,1,20,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->list("ALL","ALL","",false,501,20,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->list("ALL","ALL","",false,0,20,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.list("ALL","ALL","",false,null,time,time,1,20,user)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->list("ALL","ALL","",false,1,20,null)).isInstanceOf(BusinessException.class);
    }
    @Test void httpRequiresConsolePermissionAndReportsExistingReadCapability() throws Exception {
        mvc.perform(get("/api/executions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/executions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER")))).andExpect(status().isForbidden());
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"))
            mvc.perform(get("/api/executions").param("query",prefix).with(jwt().jwt(j->j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_"+role))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.contract").value("EXECUTION_CATALOG_V1"));
    }
}
