package com.eap.platform.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independent JVMs share only an isolated, loopback MySQL database and public JWKS. */
@EnabledIfEnvironmentVariable(named = "EAP_SESSION_CLUSTER_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true", "server.address=127.0.0.1", "eap.security.mode=JWT",
        "eap.security.issuer-uri=https://idp.invalid.example/realm/eap",
        "eap.security.audience=eap-test", "eap.security.roles-claim=roles"})
@ActiveProfiles("test")
class CrossInstanceSessionMySqlIT {
    private static final RSAKey KEY;
    private static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("ephemeral-test").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                byte[] bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            JWKS.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    @DynamicPropertySource static void jwks(DynamicPropertyRegistry registry) {
        registry.add("eap.security.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stopJwks() { JWKS.stop(0); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @Timeout(360)
    void revocationSurvivesOtherInstanceRestartAndFailsClosedWhenTableIsUnavailable() throws Exception {
        assertThat(System.getProperty("spring.datasource.url")).isEqualTo("jdbc:mysql://127.0.0.1:3306/eap_it");
        Path dir = Files.createTempDirectory("eap-session-cluster-");
        Process child = null;
        try {
            child = startChild(dir, "first");
            int second = awaitPort(child, dir.resolve("first.ready"));
            String revoked = token("revoked-" + UUID.randomUUID());
            String other = token("other-" + UUID.randomUUID());
            assertThat(request(port, "GET", "/api/platform/me", revoked)).isEqualTo(200);
            assertThat(request(second, "GET", "/api/platform/me", revoked)).isEqualTo(200);
            assertThat(request(port, "POST", "/api/platform/session/logout", revoked)).isEqualTo(200);
            assertThat(request(second, "GET", "/api/platform/me", revoked)).isEqualTo(401);
            assertThat(request(second, "GET", "/api/platform/me", other)).isEqualTo(200);

            stop(child); child = startChild(dir, "restarted");
            second = awaitPort(child, dir.resolve("restarted.ready"));
            assertThat(request(second, "GET", "/api/platform/me", revoked)).isEqualTo(401);
            assertThat(request(second, "GET", "/api/platform/me", other)).isEqualTo(200);

            String concurrent = token("concurrent-" + UUID.randomUUID());
            long before = db.queryForObject("select count(*) from revoked_access_tokens", Long.class);
            var gate = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            final int peerPort = second;
            try {
                var first = pool.submit(() -> { gate.await(); return request(port, "POST", "/api/platform/session/logout", concurrent); });
                var peer = pool.submit(() -> { gate.await(); return request(peerPort, "POST", "/api/platform/session/logout", concurrent); });
                gate.countDown();
                int firstStatus = first.get(30, TimeUnit.SECONDS), peerStatus = peer.get(30, TimeUnit.SECONDS);
                assertThat(firstStatus).isIn(200, 401);
                assertThat(peerStatus).isIn(200, 401);
                assertThat(firstStatus == 200 || peerStatus == 200).isTrue();
            } finally { pool.shutdownNow(); }
            assertThat(db.queryForObject("select count(*) from revoked_access_tokens", Long.class)).isEqualTo(before + 1);
            assertThat(request(port, "GET", "/api/platform/me", concurrent)).isEqualTo(401);
            assertThat(request(second, "GET", "/api/platform/me", concurrent)).isEqualTo(401);

            db.execute("rename table revoked_access_tokens to revoked_access_tokens_unavailable");
            try {
                assertThat(request(port, "GET", "/api/platform/me", other)).isEqualTo(503);
                assertThat(request(second, "GET", "/api/platform/me", other)).isEqualTo(503);
            } finally { db.execute("rename table revoked_access_tokens_unavailable to revoked_access_tokens"); }
            assertThat(request(second, "GET", "/api/platform/me", revoked)).isEqualTo(401);
            assertThat(request(second, "GET", "/api/platform/me", other)).isEqualTo(200);
        } finally { stop(child); }
    }

    private Process startChild(Path dir, String name) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-Xmx384m", "-cp", System.getProperty("java.class.path"),
                CrossInstanceTestBackend.class.getName(), Integer.toString(JWKS.getAddress().getPort()),
                dir.resolve(name + ".ready").toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve(name + ".log").toFile()).start();
    }

    private int awaitPort(Process child, Path ready) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        while (!Files.isRegularFile(ready) && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(250);
        assertThat(Files.isRegularFile(ready)).as("second backend became ready").isTrue();
        return Integer.parseInt(Files.readString(ready).trim());
    }

    private void stop(Process child) throws Exception {
        if (child == null) return;
        child.destroy();
        if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(15, TimeUnit.SECONDS); }
    }

    private int request(int serverPort, String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + path))
                .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String token(String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://idp.invalid.example/realm/eap").subject(subject)
                .audience("eap-test").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("roles", List.of("OPERATOR")).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }
}
