package com.eap.platform.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap", "eap.security.jwk-set-uri=https://idp.invalid.example/jwks"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class PersonalMemoryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entityManager;
    private org.springframework.test.web.servlet.request.RequestPostProcessor user(String name, String role) {
        return jwt().jwt(j -> j.issuer("https://idp.invalid.example/realm/eap").subject(name))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
    private String body(String key, String content, Long expires, boolean confirmed) throws Exception {
        return mapper.writeValueAsString(new PersonalMemoryController.CreateRequest(key, content, expires, confirmed));
    }
    private String create(String name, String key, String content) throws Exception {
        String response = mvc.perform(post("/api/personal-memories").with(user(name, "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(key, content, null, true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asText();
    }

    @Test void authenticatedOwnerIsolationRevocationAndAuditContainNoContent() throws Exception {
        String key = UUID.randomUUID().toString(), privateContent = "PRIVATE_SYNTHETIC_MEMORY";
        String id = create("alice", key, privateContent);
        mvc.perform(get("/api/platform/me").with(user("alice", "OPERATOR"))).andExpect(jsonPath("$.data.capabilities.manageOwnMemory").value(true));
        mvc.perform(get("/api/platform/me").with(user("observer", "VIEWER"))).andExpect(jsonPath("$.data.capabilities.manageOwnMemory").value(false));
        mvc.perform(get("/api/personal-memories").with(user("alice", "OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].content").value(privateContent));
        mvc.perform(get("/api/personal-memories").with(user("bob", "PLATFORM_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        var differentIssuer = jwt().jwt(j -> j.issuer("https://other.invalid/realm").subject("alice"))
                .authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        mvc.perform(get("/api/personal-memories").with(differentIssuer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(post("/api/personal-memories/" + id + "/revoke").with(user("bob", "PLATFORM_ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/personal-memories").with(user("observer", "VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/personal-memories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/personal-memories/" + id + "/revoke").with(user("alice", "OPERATOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/personal-memories/" + id + "/revoke").with(user("alice", "OPERATOR"))).andExpect(status().isOk());
        assertThat(db.queryForObject("select content from personal_memories where memory_id=?", String.class, id)).isNull();
        mvc.perform(get("/api/personal-memories").with(user("alice", "OPERATOR"))).andExpect(jsonPath("$.data.items").isEmpty());
        entityManager.flush();
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_type='PERSONAL_MEMORY' and resource_id=?", Long.class, id)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from audit_logs where resource_type='PERSONAL_MEMORY' and resource_id=? and (before_json is not null or after_json is not null)", Long.class, id)).isZero();
    }

    @Test void replayIsIdempotentAndCannotResurrectRevokedContent() throws Exception {
        String key = UUID.randomUUID().toString(), id = create("alice", key, "first synthetic value");
        String replay = create("alice", key, "first synthetic value");
        assertThat(replay).isEqualTo(id);
        mvc.perform(post("/api/personal-memories").with(user("alice", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content(body(key, "different", null, true))).andExpect(status().isConflict());
        mvc.perform(post("/api/personal-memories/" + id + "/revoke").with(user("alice", "OPERATOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/personal-memories").with(user("alice", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content(body(key, "first synthetic value", null, true))).andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from personal_memories where request_key=?", Long.class, key)).isEqualTo(1);
    }

    @Test void theSameRequestKeyIsScopedToEachOwner() throws Exception {
        String key = UUID.randomUUID().toString();
        String aliceId = create("alice", key, "alice synthetic memory");
        String bobId = create("bob", key, "bob synthetic memory");
        assertThat(aliceId).isNotEqualTo(bobId);
        mvc.perform(post("/api/personal-memories/" + aliceId + "/revoke").with(user("alice", "OPERATOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/personal-memories").with(user("bob", "OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].content").value("bob synthetic memory"));
    }

    @Test void simultaneousIdenticalRequestsCreateAtMostOneMemory() throws Exception {
        String actor = "concurrent-" + UUID.randomUUID(), key = UUID.randomUUID().toString();
        String requestBody = body(key, "concurrent synthetic memory", null, true);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/personal-memories").with(user(actor, "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody)).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(post("/api/personal-memories").with(user(actor, "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody)).andReturn().getResponse().getStatus();
            });
            int a = first.get(30, TimeUnit.SECONDS), b = second.get(30, TimeUnit.SECONDS);
            assertThat(a).isIn(200, 409);
            assertThat(b).isIn(200, 409);
            assertThat(a == 200 || b == 200).isTrue();
            mvc.perform(post("/api/personal-memories").with(user(actor, "OPERATOR"))
                    .contentType(MediaType.APPLICATION_JSON).content(requestBody)).andExpect(status().isOk());
            assertThat(db.queryForObject("select count(*) from personal_memories where request_key=?", Long.class, key)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void expirationIsVisibleForManagementAndCannotBeReplayed() throws Exception {
        String key = UUID.randomUUID().toString();
        long expires = System.currentTimeMillis() + 60_000;
        String json = body(key, "temporary synthetic memory", expires, true);
        String created = mvc.perform(post("/api/personal-memories").with(user("alice", "OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(created).path("data").path("id").asText();
        long expired = System.currentTimeMillis() - 1;
        db.update("update personal_memories set expires_ms=? where memory_id=?", expired, id);
        mvc.perform(get("/api/personal-memories").with(user("alice", "OPERATOR"))).andExpect(jsonPath("$.data.items[0].status").value("EXPIRED"));
        mvc.perform(post("/api/personal-memories").with(user("alice", "OPERATOR")).contentType(MediaType.APPLICATION_JSON)
                .content(body(key, "temporary synthetic memory", expired, true))).andExpect(status().isConflict());
        mvc.perform(post("/api/personal-memories/" + id + "/revoke").with(user("alice", "OPERATOR"))).andExpect(status().isOk());
        assertThat(db.queryForObject("select content from personal_memories where memory_id=?", String.class, id)).isNull();
    }

    @Test void consentAndIdentityAreRequired() throws Exception {
        String json = body(UUID.randomUUID().toString(), "synthetic", null, false);
        mvc.perform(post("/api/personal-memories").with(user("alice", "OPERATOR")).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/personal-memories").with(user("observer", "VIEWER")).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mvc.perform(post("/api/personal-memories").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        var noIssuer = jwt().jwt(j -> j.claims(claims -> claims.remove("iss")).subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        mvc.perform(get("/api/personal-memories").with(noIssuer)).andExpect(status().isForbidden());
    }

    @Test void paginationStaysWithinOwnerAndRejectsInvalidCursors() throws Exception {
        String first = create("alice", UUID.randomUUID().toString(), "original");
        String owner = db.queryForObject("select owner_hash from personal_memories where memory_id=?", String.class, first);
        for (int i = 0; i < 52; i++) db.update("insert into personal_memories(memory_id,owner_hash,request_key,content,created_ms) values(?,?,?,?,?)",
                UUID.randomUUID().toString(), owner, UUID.randomUUID().toString(), "synthetic " + i, 1000L + i);
        create("bob", UUID.randomUUID().toString(), "OTHER_PRIVATE_MEMORY");
        String page = mvc.perform(get("/api/personal-memories").with(user("alice", "OPERATOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(50)).andReturn().getResponse().getContentAsString();
        assertThat(page).doesNotContain("OTHER_PRIVATE_MEMORY");
        String cursor = mapper.readTree(page).path("data").path("nextCursor").asText();
        String second = mvc.perform(get("/api/personal-memories").param("cursor", cursor).with(user("alice", "OPERATOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3)).andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(second).doesNotContain("OTHER_PRIVATE_MEMORY");
        mvc.perform(get("/api/personal-memories").param("cursor", "../bad").with(user("alice", "OPERATOR"))).andExpect(status().isBadRequest());
    }
}
