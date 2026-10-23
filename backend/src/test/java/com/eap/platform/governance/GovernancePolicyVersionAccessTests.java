package com.eap.platform.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class GovernancePolicyVersionAccessTests {
    @Autowired MockMvc mvc; @Autowired GovernancePolicyService policies; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    Map<String,Object> body() { return new LinkedHashMap<>(Map.of("name","PUBLIC-policy","policyType","PRIVACY","scopeType","GLOBAL","scopeValue","","ruleJson","{}","enforcementMode","WARN","enabled",false)); }
    GovernancePolicyDtos.PolicyResponse fixture() {return policies.create(new GovernancePolicyDtos.PolicyRequest("PUBLIC-"+UUID.randomUUID(),PolicyType.PRIVACY,"GLOBAL","","{}",EnforcementMode.WARN,false));}
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void centralCapabilitiesControlHistoryEditingAndRollback(String role) throws Exception {
        var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role));boolean manage=Set.of("GOVERNANCE_ADMIN","PLATFORM_ADMIN").contains(role);
        var p=fixture();String path="/api/policies/"+p.id();String digest=policies.versions(p.id()).versions().getFirst().snapshotDigest();
        mvc.perform(get("/api/platform/me").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.capabilities.governance").value(manage));
        mvc.perform(get(path+"/versions").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentRevision").value(0));
        var update=body();update.put("expectedRevision",0);update.put("name","PUBLIC-edited");
        mvc.perform(put(path).with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update))).andExpect(status().is(manage?200:403));
        var rollback=Map.of("expectedRevision",1,"sourceRevision",0,"sourceDigest",digest);
        mvc.perform(post(path+"/rollback").with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(rollback))).andExpect(status().is(manage?200:403));
        assertThat(db.queryForObject("select revision from governance_policies where id=?",Long.class,p.id())).isEqualTo(manage?2L:0L);
        assertThat(db.queryForObject("select count(*) from governance_policy_versions where policy_id=?",Long.class,p.id())).isEqualTo(manage?3L:1L);
    }
    @Test void anonymousAndUnrelatedRolesCannotReadHistoryOrPublish() throws Exception {
        var p=fixture();String path="/api/policies/"+p.id();var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_OWNER"));
        mvc.perform(get(path+"/versions")).andExpect(status().isUnauthorized());
        mvc.perform(get(path+"/versions").with(auth)).andExpect(status().isForbidden());
        mvc.perform(post(path+"/rollback").with(auth).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void missingRevisionIsBadRequestAndStaleRevisionIsConflictWithoutChangingThePolicy() throws Exception {
        var p=fixture();var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"));String path="/api/policies/"+p.id();
        mvc.perform(put(path).with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body()))).andExpect(status().isBadRequest());
        var stale=body();stale.put("expectedRevision",9);
        mvc.perform(put(path).with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(stale))).andExpect(status().isConflict());
        mvc.perform(post(path+"/rollback").with(auth).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select revision from governance_policies where id=?",Long.class,p.id())).isZero();
    }
    @Test void invalidRulesAndScopeAreRejectedWithoutPublishing() throws Exception {
        var p=fixture();var auth=jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"));
        for(String rule:List.of("[]","null","{","{\"padding\":\""+"a".repeat(16384)+"\"}")) {
            var body=body();body.put("expectedRevision",0);body.put("ruleJson",rule);
            mvc.perform(put("/api/policies/"+p.id()).with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
            emClear();
        }
        var invalidScope=body();invalidScope.put("expectedRevision",0);invalidScope.put("scopeType","UNSUPPORTED");
        mvc.perform(put("/api/policies/"+p.id()).with(auth).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(invalidScope))).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("select count(*) from governance_policy_versions where policy_id=?",Long.class,p.id())).isEqualTo(1);
    }
    @Autowired jakarta.persistence.EntityManager em;
    void emClear(){em.clear();}
}
