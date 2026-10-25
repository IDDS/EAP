package com.eap.platform.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false",
        "eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/eap",
        "eap.security.jwk-set-uri=https://idp.invalid.example/jwks",
        "eap.governance-data.ingestion-token=PUBLIC-INGESTION-FIXTURE",
        "eap.governance-data.approval-token=PUBLIC-APPROVAL-FIXTURE"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class GovernanceApprovalAccessTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired GovernancePolicyService policies;
    @Autowired GovernancePreflightService preflight;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    final String model="access-"+UUID.randomUUID();
    final String key="access-"+UUID.randomUUID();
    RequestPostProcessor identity(String role) {
        return jwt().jwt(token->token.subject("PUBLIC-auth-"+role)).authorities(new SimpleGrantedAuthority("ROLE_"+role));
    }
    GovernancePolicyDtos.PolicyResponse policy() {
        return policies.create(new GovernancePolicyDtos.PolicyRequest("PUBLIC approval",PolicyType.PRIVACY,"MODEL",model,
                "{\"sensitivity\":\"PUBLIC\"}",EnforcementMode.APPROVAL_REQUIRED,true,null));
    }
    GovernanceDataDtos.PreflightRequest request() {
        return new GovernanceDataDtos.PreflightRequest(key,"event-"+key,db.queryForObject("select min(id) from agents",Long.class),
                null,null,"c".repeat(32),GovernanceDataDirection.INPUT_TO_MODEL,"PUBLIC_FIXTURE",GovernanceDataSensitivity.PUBLIC,
                false,false,0,0,"fixture","PUBLIC",model,List.of(),null,Instant.now());
    }
    String approvalBody() {return "{\"approver\":\"FORGED-BODY-IDENTITY\",\"reason\":\"public fixture\"}";}

    @ParameterizedTest @CsvSource({"VIEWER,approve,false","OPERATOR,approve,false","GOVERNANCE_ADMIN,approve,true","PLATFORM_ADMIN,approve,true",
            "VIEWER,reject,false","OPERATOR,reject,false","GOVERNANCE_ADMIN,reject,true","PLATFORM_ADMIN,reject,true"})
    void consoleApprovalUsesCentralCapabilityAndAuthenticatedAuditIdentity(String role,String action,boolean allowed) throws Exception {
        policy();preflight.evaluate(request());
        mvc.perform(get("/api/platform/me").with(identity(role))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.governance").value(allowed));
        var result=mvc.perform(post("/api/governance/preflight-decisions/"+key+"/"+action).with(identity(role))
                .contentType(MediaType.APPLICATION_JSON).content(approvalBody()));
        if(allowed) {
            result.andExpect(status().isOk()).andExpect(jsonPath("$.data.approvedBy").value("PUBLIC-auth-"+role))
                    .andExpect(jsonPath("$.data.approvalStatus").value(action.equals("approve")?"APPROVED":"REJECTED"));
            assertThat(db.queryForObject("select actor from audit_logs where resource_id=? and action='GOVERNANCE_APPROVAL'",String.class,key)).isEqualTo("PUBLIC-auth-"+role);
        } else {
            result.andExpect(status().isForbidden());assertThat(preflight.get(key).approvalStatus()).isEqualTo(GovernanceApprovalStatus.PENDING);
            assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='GOVERNANCE_APPROVAL'",Long.class,key)).isZero();
        }
    }

    @ParameterizedTest @CsvSource({"VIEWER,false","OPERATOR,false","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true","DATA_OWNER,false","NONE,false"})
    void consolePreflightUsesCentralCapabilityWithoutMachineCredentialEscalation(String role,boolean allowed) throws Exception {
        policy();long before=db.queryForObject("select count(*) from governance_preflight_decisions",Long.class);
        var call=post("/api/governance/preflight").with(identity(role)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request()));
        // A supplied machine credential must not override a signed-in user's missing capability.
        if(!allowed)call.header("X-EAP-Governance-Token","PUBLIC-INGESTION-FIXTURE");
        var result=mvc.perform(call);
        if(allowed)result.andExpect(status().isOk()).andExpect(jsonPath("$.data.approvalContextState").value("CURRENT"));
        else {result.andExpect(status().isForbidden());assertThat(db.queryForObject("select count(*) from governance_preflight_decisions",Long.class)).isEqualTo(before);}
    }

    @ParameterizedTest @ValueSource(strings={"approve","reject"})
    void anonymousApprovalCannotUseAMachineHeaderToBypassConsoleAuthorization(String action) throws Exception {
        policy();preflight.evaluate(request());
        mvc.perform(post("/api/governance/preflight-decisions/"+key+"/"+action).header("X-EAP-Governance-Approval-Token","PUBLIC-APPROVAL-FIXTURE")
                .contentType(MediaType.APPLICATION_JSON).content(approvalBody())).andExpect(status().isUnauthorized());
        assertThat(preflight.get(key).approvalStatus()).isEqualTo(GovernanceApprovalStatus.PENDING);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void authenticatedApprovalReachesFreshnessChecksAndPreservesStaleRows(boolean legacy) throws Exception {
        var p=policy();preflight.evaluate(request());em.flush();
        if(legacy) {db.update("update governance_preflight_decisions set policy_context_digest=null where decision_key=?",key);em.clear();}
        else policies.update(p.id(),new GovernancePolicyDtos.PolicyRequest("changed",p.policyType(),p.scopeType(),p.scopeValue(),p.ruleJson(),p.enforcementMode(),true,0L));
        var before=db.queryForMap("select * from governance_preflight_decisions where decision_key=?",key);
        mvc.perform(post("/api/governance/preflight-decisions/"+key+"/approve").with(identity("GOVERNANCE_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(approvalBody())).andExpect(status().isConflict());
        assertThat(db.queryForMap("select * from governance_preflight_decisions where decision_key=?",key)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_id=? and action='GOVERNANCE_APPROVAL'",Long.class,key)).isZero();
    }

    @ParameterizedTest @CsvSource({"PUBLIC-INGESTION-FIXTURE,200","PUBLIC-INVALID,401","MISSING,401"})
    void machinePreflightStillRequiresItsOriginalCredential(String token,int status) throws Exception {
        policy();var call=post("/api/governance/preflight").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request()));
        if(!token.equals("MISSING"))call.header("X-EAP-Governance-Token",token);
        mvc.perform(call).andExpect(status().is(status));
    }
}
