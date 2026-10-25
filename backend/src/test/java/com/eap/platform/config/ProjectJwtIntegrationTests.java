package com.eap.platform.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.security.mode=JWT", "eap.security.issuer-uri=https://idp.example/realm",
        "eap.security.audience=eap-alpha-api", "eap.security.project-key=alpha", "eap.security.initialize-project-binding=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProjectJwtIntegrationTests {
    static final RSAKey key;
    static final HttpServer jwks;
    static {
        try {
            key = new RSAKeyGenerator(2048).keyID("ephemeral-project-test").generate();
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/jwks", e -> {
                byte[] json = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, json.length); e.getResponseBody().write(json); e.close();
            }); jwks.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("eap.security.jwk-set-uri", () -> "http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
        String mysql = System.getenv("EAP_PROJECT_TEST_MYSQL");
        registry.add("spring.datasource.url", () -> mysql == null
                ? "jdbc:h2:mem:project_jwt;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
                : mysql + "project_jwt?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", () -> mysql == null ? "sa" : "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> mysql == null ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform", () -> mysql == null ? "org.hibernate.dialect.H2Dialect" : "org.hibernate.dialect.MySQLDialect");
    }

    @Autowired MockMvc mvc;
    @AfterAll static void close() { jwks.stop(0); }

    @ParameterizedTest @ValueSource(strings = {"VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"})
    void signedProjectTokenRetainsCentralRoleCapabilities(String role) throws Exception {
        mvc.perform(get("/api/platform/me").header("Authorization", "Bearer " + token("alpha", "eap-alpha-api", role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.project.projectKey").value("alpha"))
                .andExpect(jsonPath("$.data.project.isolationMode").value("DEDICATED_PROJECT"))
                .andExpect(jsonPath("$.data.capabilities.deployAgents").value(List.of("OPERATOR", "PLATFORM_ADMIN").contains(role)));
    }

    @Test void crossProjectAndMalformedClaimsCannotReadAnyConsoleRoute() throws Exception {
        for (Object project : List.of("beta", "Alpha", "alpha ", List.of("alpha"), Map.of("key", "alpha"), 1)) {
            String signed = token(project, "eap-alpha-api", "PLATFORM_ADMIN");
            for (String path : List.of("/api/platform/me", "/api/agents", "/api/observability/agent-executions", "/api/observability/content")) {
                mvc.perform(get(path).header("Authorization", "Bearer " + signed).header("X-EAP-Project", "alpha").param("projectKey", "alpha"))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @Test void missingClaimOrWrongAudienceCannotUseAdministratorRoleToMutate() throws Exception {
        for (String signed : List.of(token(null, "eap-alpha-api", "PLATFORM_ADMIN"), token("alpha", "eap-beta-api", "PLATFORM_ADMIN"))) {
            mvc.perform(post("/api/agents").header("Authorization", "Bearer " + signed)
                    .contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/agents").header("Authorization", "Bearer " + token("alpha", "eap-alpha-api", "VIEWER"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test void anonymousMetadataCannotGrantProjectOrMachineAccess() throws Exception {
        mvc.perform(get("/api/platform/status")).andExpect(status().isOk());
        mvc.perform(get("/api/platform/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/observability/otlp/v1/traces").header("X-EAP-Project", "alpha")
                .contentType("application/json").content("{\"resourceSpans\":[]}")).andExpect(status().isUnauthorized());
    }

    private static String token(Object project, String audience, String role) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://idp.example/realm").subject("project-user").audience(audience)
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(120))).claim("roles", List.of(role));
        if (project != null) claims.claim(ProjectIsolation.CLAIM, project);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
}
