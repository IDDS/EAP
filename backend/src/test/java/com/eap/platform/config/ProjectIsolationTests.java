package com.eap.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class ProjectIsolationTests {
    private final DriverManagerDataSource ds = database();
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);

    ProjectIsolationTests() throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/V93__project_deployment_binding.sql")) {
            for (String sql : new String(resource.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) jdbc.execute(sql);
            }
        }
    }

    private static DriverManagerDataSource database() {
        String name = "project_" + UUID.randomUUID().toString().replace("-", "");
        String server = System.getenv("EAP_PROJECT_TEST_MYSQL");
        return server == null ? new DriverManagerDataSource("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
                : new DriverManagerDataSource(server + name + "?useSSL=false&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=true", "root", "");
    }

    private SecurityProperties properties(String project, boolean initialize) {
        var p = new SecurityProperties(); p.setMode(SecurityProperties.Mode.JWT);
        p.setIssuerUri("https://idp.example/realm"); p.setAudience("eap-" + project + "-api");
        p.setProjectKey(project); p.setInitializeProjectBinding(initialize); return p;
    }

    private ProjectIsolation start(SecurityProperties p) {
        return new ProjectIsolation(p, jdbc, new DataSourceTransactionManager(ds));
    }

    @Test void legacyDatabaseStaysUnbound() {
        assertThat(start(new SecurityProperties()).context().isolationMode()).isEqualTo("SHARED_TRUST_DOMAIN");
        assertThat(jdbc.queryForObject("select project_key from platform_project_binding where id=1", String.class)).isNull();
    }

    @Test void bindingRequiresExplicitInitializationAndCannotBeChangedOrDisabled() {
        assertThatThrownBy(() -> start(properties("alpha", false))).isInstanceOf(IllegalStateException.class);
        var a = start(properties("alpha", true));
        var original = jdbc.queryForMap("select * from platform_project_binding where id=1");
        assertThat(start(properties("alpha", false)).context()).isEqualTo(a.context());
        assertThat(start(properties("alpha", true)).context()).isEqualTo(a.context());
        assertThatThrownBy(() -> start(properties("beta", true))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> start(new SecurityProperties())).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("select * from platform_project_binding where id=1")).isEqualTo(original);
    }

    @ParameterizedTest @ValueSource(strings = {"Alpha", "alpha ", "alpha_beta", "../alpha", "α", "a23456789012345678901234567890123456789012345678901234"})
    void invalidProjectCannotBindDatabase(String key) {
        assertThatThrownBy(() -> start(properties(key, true))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select project_key from platform_project_binding where id=1", String.class)).isNull();
    }

    @Test void developmentOrIncompleteJwtConfigurationCannotBind() {
        for (String missing : List.of("mode", "issuer", "audience", "project")) {
            var p = properties("alpha", true);
            switch (missing) {
                case "mode" -> p.setMode(SecurityProperties.Mode.DEVELOPMENT);
                case "issuer" -> p.setIssuerUri("");
                case "audience" -> p.setAudience("");
                case "project" -> p.setProjectKey("");
            }
            assertThatThrownBy(() -> start(p)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void missingSingletonFailsClosedRatherThanRebinding() {
        jdbc.update("delete from platform_project_binding where id=1");
        assertThatThrownBy(() -> start(properties("alpha", true))).isInstanceOf(IllegalStateException.class);
    }

    @Test void projectClaimMustBeAnExactScalarEvenForPlatformAdmin() {
        var isolation = start(properties("alpha", true));
        for (Object claim : List.of("beta", "Alpha", "alpha ", List.of("alpha"), Map.of("key", "alpha"), 1, true)) {
            assertThat(isolation.validate(jwt(claim)).hasErrors()).isTrue();
        }
        assertThat(isolation.validate(jwt(null)).hasErrors()).isTrue();
        assertThat(isolation.validate(jwt("alpha")).hasErrors()).isFalse();
    }

    @Test void concurrentProjectsCannotBothBindTheSameDatabase() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            var results = List.of("alpha", "beta").stream().map(key -> executor.submit(() -> {
                ready.countDown(); go.await();
                try { start(properties(key, true)); return key; }
                catch (IllegalStateException conflict) { return "REJECTED"; }
            })).toList();
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); go.countDown();
            var values = List.of(results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS), results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(values.stream().filter("REJECTED"::equals).count()).isEqualTo(1);
            assertThat(values).contains(jdbc.queryForObject("select project_key from platform_project_binding where id=1", String.class));
        }
    }

    private Jwt jwt(Object project) {
        var builder = Jwt.withTokenValue("synthetic").header("alg", "none").subject("operator")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("roles", List.of("PLATFORM_ADMIN"));
        if (project != null) builder.claim(ProjectIsolation.CLAIM, project);
        return builder.build();
    }

    @Test void runtimeScopesHaveUnambiguousProjectSuffixes() {
        var project = start(properties("alpha", true));
        project.requireRuntimeScope("eap-alpha-agents"); project.requireRuntimeScope("eap-alpha-ax");
        for (String scope : List.of("eap-agents", "eap-alpha", "eap-beta-agents", "eap-alpha-ax-agents", "eap-alpha-agents-extra", "")) {
            assertThatThrownBy(() -> project.requireRuntimeScope(scope))
                    .isInstanceOf(com.eap.platform.substrate.SubstrateClient.SubstrateException.class);
        }
    }

    @Test void everyNativeOperationRejectsAnotherProjectBeforeOpeningTransport() {
        var project = start(properties("alpha", true));
        var client = new com.eap.platform.substrate.GrpcSubstrateClient(new com.eap.platform.substrate.SubstrateProperties(), project);
        var other = new com.eap.platform.substrate.SubstrateActorRef("eap-beta-agents", "example");
        var template = new com.eap.platform.substrate.SubstrateClient.TemplateSpec(other.atespace(), "example", "fixed", List.of(), Map.of(), "/ready", 80, "1", "1Gi", false, Map.of(), "unused");
        List<Runnable> operations = List.of(() -> client.get(other), () -> client.inspect(other), () -> client.locate(other),
                () -> client.suspend(other), () -> client.resume(other), () -> client.revert(other),
                () -> client.deleteActor(other), () -> client.createActor(other, "example"), () -> client.createActorWithIdentity(other, "example"),
                () -> client.listActors(other.atespace(), ""), () -> client.ensureAtespace(other.atespace()),
                () -> client.goldenStatus(other.atespace(), "example"), () -> client.createTemplate(template),
                () -> client.setEgressHostnames(other, List.of()), () -> client.setEgressHostnames(other, List.of("example.invalid")));
        for (Runnable operation : operations) assertThatThrownBy(operation::run)
                .isInstanceOf(com.eap.platform.substrate.SubstrateClient.SubstrateException.class)
                .satisfies(error -> assertThat(((com.eap.platform.substrate.SubstrateClient.SubstrateException) error).code()).isEqualTo("PERMISSION_DENIED"));
    }

    @Test void axOperationsRejectAnotherProjectBeforeOpeningTransport() {
        var properties = new com.eap.platform.ax.AxProperties(); properties.setAtespace("eap-beta-ax");
        var client = new com.eap.platform.ax.GrpcAxClient(properties, start(properties("alpha", true)));
        List<Runnable> operations = List.of(() -> client.get("task"), () -> client.ensure("task", Map.of()),
                () -> client.ensure("task", "fixed", Map.of()), () -> client.resume("task"), () -> client.suspend("task"));
        for (Runnable operation : operations) assertThatThrownBy(operation::run)
                .isInstanceOf(com.eap.platform.substrate.SubstrateClient.SubstrateException.class);
    }

    @Test void sourceBuildCannotAccessAnotherProjectNamespaceAndRepositoriesAreDistinct() {
        var p = new com.eap.platform.substrate.SubstrateProperties();
        p.setBuildNamespace("eap-beta-builds"); p.setLocalRegistryPrefix("registry.example/");
        var project = start(properties("alpha", true));
        project.requireBuildNamespace("eap-alpha-builds");
        var builder = new com.eap.platform.deployment.KanikoImageBuilder(p, new com.fasterxml.jackson.databind.ObjectMapper(), project);
        assertThat(builder.repository(1L)).isEqualTo("registry.example/eap-alpha-src-a1");
        assertThat(builder.destination(1L, 2)).isEqualTo("registry.example/eap-alpha-src-a1:v2");
        List<Runnable> operations = List.of(() -> builder.start(null), () -> builder.status("job"),
                () -> builder.logTail("job", 10), () -> builder.delete("job"));
        for (Runnable operation : operations) assertThatThrownBy(operation::run)
                .isInstanceOf(com.eap.platform.deployment.ImageBuilder.BuildException.class)
                .hasMessage("构建命名空间不属于当前项目");
    }
}
