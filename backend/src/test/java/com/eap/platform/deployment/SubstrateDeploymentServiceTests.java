package com.eap.platform.deployment;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.AgentRegistryService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.safety.GenseeSafetyService;
import com.eap.platform.safety.SafetyDecision;
import com.eap.platform.safety.SafetyDtos;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubstrateDeploymentServiceTests {
    private static final String RUNNER = "localhost:5001/eap-agent-runner@sha256:" + "a".repeat(64);

    private final SubstrateDeploymentRepository repository = mock(SubstrateDeploymentRepository.class);
    private final AgentRuntimeInstanceRepository runtimes = mock(AgentRuntimeInstanceRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentRegistryService registry = mock(AgentRegistryService.class);
    private final SubstrateClient client = mock(SubstrateClient.class);
    private final GenseeSafetyService safety = mock(GenseeSafetyService.class);
    private final ImageBuilder builder = mock(ImageBuilder.class);
    private final SubstrateProperties properties = new SubstrateProperties();
    private final WorkflowExecutionProperties workflow = new WorkflowExecutionProperties();
    private final com.eap.platform.observability.ObservationProperties observation = new com.eap.platform.observability.ObservationProperties();
    private com.eap.platform.registry.RuntimeWorkService work;
    private org.springframework.jdbc.core.JdbcTemplate workDb;
    private SubstrateDeploymentService service;
    private SubstrateDeployment stored;
    private final PlatformRuntimeCreationService owned=mock(PlatformRuntimeCreationService.class);
    private final com.eap.platform.registry.RuntimeConnectionProperties identity=new com.eap.platform.registry.RuntimeConnectionProperties();

    @BeforeEach
    void setUp() {
        var workDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:chat_guard_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        workDb = new org.springframework.jdbc.core.JdbcTemplate(workDataSource);
        workDb.execute("create table runtime_work_actors(actor_key varchar(128) primary key,reclaim_state varchar(24) not null,reclaim_owner varchar(36))");
        workDb.execute("create table runtime_work_claims(work_id varchar(36) primary key,actor_key varchar(128) not null,work_kind varchar(24) not null,state varchar(16) not null,created_at timestamp,updated_at timestamp)");
        work = new com.eap.platform.registry.RuntimeWorkService(workDb,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(workDataSource));
        identity.setSigningKey("synthetic-unit-identity-signing-material");identity.setDeliveryPrivateKey("synthetic-test-private");identity.setDeliveryPublicKey("synthetic-test-public");
        when(owned.create(any(),anyString(),any())).thenReturn("eap-agents/a7-v1-123456789abc");
        properties.setEnabled(true);
        properties.setRunnerImage(RUNNER);
        properties.setGatewayUrl("http://backend.eap.svc.cluster.local:8080");
        properties.setGatewayHost("backend.eap.svc.cluster.local");
        properties.setGatewayToken("gateway-token");
        observation.setIngestionToken("observation-test-token");
        workflow.setEnabled(true);
        workflow.setToken("task-token");
        workflow.setAllowedHosts("atenet-router.ate-system.svc");
        Agent agent = new Agent();
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);
        when(agents.findById(7L)).thenReturn(Optional.of(agent));
        when(repository.findFirstByAgentIdOrderByVersionDesc(7L)).thenReturn(Optional.empty());
        when(repository.save(any(SubstrateDeployment.class))).thenAnswer(call -> {
            SubstrateDeployment deployment = call.getArgument(0);
            if (deployment.id == null) deployment.id = 1L;
            if (deployment.createdAt == null) deployment.createdAt = java.time.Instant.now();
            stored = deployment;
            return deployment;
        });
        when(repository.findById(1L)).thenAnswer(call -> Optional.ofNullable(stored));
        allow(SafetyDecision.ALLOW);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new SubstrateDeploymentService(repository, agents, runtimes, registry, client,
                properties, workflow, observation, safety, mock(AuditLogRepository.class), builder, new ObjectMapper().registerModule(new JavaTimeModule()), transactions,owned,identity,mock(com.eap.platform.registry.RuntimeConnectionService.class), work);
    }

    @Test void missingReceiptIsReadOnlyAndDoesNotMeanTheOriginalPostCannotCommit() {
        var receipt = service.receipt(7L, "original-key");
        assertThat(receipt.status()).isEqualTo("NOT_FOUND");
        assertThat(receipt.deploymentKey()).isNull();
        verify(repository).findByAgentIdAndSubmissionKey(7L, "original-key");
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(client, owned, safety, agents);
    }

    @Test void receiptRemainsQueryableWithoutCurrentRuntimeOrAgentEligibility() {
        var request = controlledRunner(null, null, "deepseek-flash", "RUNNER");
        service.submit(7L, request, "operator");
        when(repository.findByAgentIdAndSubmissionKey(7L, request.requestKey())).thenReturn(Optional.of(stored));
        properties.setEnabled(false); clearInvocations(repository, client, owned, agents, safety);
        var receipt = service.receipt(7L, request.requestKey());
        assertThat(receipt.version()).isEqualTo(1);
        assertThat(receipt.status()).isEqualTo("QUEUED");
        verify(repository).findByAgentIdAndSubmissionKey(7L, request.requestKey());
        verifyNoMoreInteractions(repository); verifyNoInteractions(client, owned, agents, safety);
    }

    @Test void invalidReceiptKeysNeverAccessStorage() {
        for (String key : List.of("", "with/slash", "a".repeat(65)))
            assertThatThrownBy(() -> service.receipt(7L, key)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository, client, owned);
    }

    @Test
    void runnerDeploymentAdvancesToReadyWithGatewayOnlyEgressAndNoSecretsInConfig() {
        var response = service.submit(7L, runner(Map.of("TONE", "formal"), List.of()), "operator");
        assertThat(response.status()).isEqualTo("QUEUED");
        assertThat(response.templateName()).isEqualTo("eap-a7-v1");
        assertThat(response.imageRef()).isEqualTo(RUNNER);
        assertThat(stored.configJson).doesNotContain("task-token").doesNotContain("gateway-token").doesNotContain("observation-test-token");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));

        service.processNext();
        ArgumentCaptor<SubstrateClient.TemplateSpec> spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().projectIdentity()).isTrue();
        assertThat(spec.getValue().env()).doesNotContainKeys("EAP_TASK_TOKEN", "EAP_GATEWAY_TOKEN", "EAP_OBSERVABILITY_TOKEN")
                .containsEntry("EAP_IDENTITY_PUBLIC_KEY", "synthetic-test-public").containsEntry("EAP_IDENTITY_DIRECTORY", "/run/eap-identity").containsEntry("AGENT_MODEL", "deepseek-flash").containsEntry("EAP_AGENT_ID", "7").containsEntry("TONE", "formal");
        assertThat(stored.status).isEqualTo("BUILDING_GOLDEN");

        when(client.goldenStatus("eap-agents", "eap-a7-v1")).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        service.processNext();
        assertThat(stored.status).isEqualTo("CREATING_INSTANCE");

        service.processNext();
        assertThat(stored.status).isEqualTo("CONNECTING_IDENTITY");
        service.processNext();
        verify(owned).connect(eq(stored),eq(stored.deploymentKey.substring(4)),anyString());
        assertThat(stored.status).isEqualTo("READY");
        assertThat(stored.instanceKey).matches("eap-agents/a7-v1-[a-z0-9]{12}");
        verify(owned).create(eq(stored),eq(stored.deploymentKey.substring(4)),eq(List.of("backend.eap.svc.cluster.local")));
        verify(registry,never()).registerRuntime(any(),any());
    }

    @Test void newRunnerFailsClosedWithoutDeliveryConfiguration() {
        identity.setDeliveryPrivateKey("");
        assertThatThrownBy(()->service.submit(7L,runner(Map.of(),List.of()),"operator")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client,owned);
    }

    @Test void kagentRequiresPinnedImageAndSecureIdentityBeforePublication() {
        for (String image : List.of("", "localhost:5001/kagent:latest", "localhost:5001/kagent@sha256:short")) {
            properties.setKagentRunnerImage(image);
            assertThat(service.capabilities().kagentRunnerAvailable()).isFalse();
            assertThatThrownBy(() -> service.submit(7L, frameworkRunner("KAGENT_ADK", "RUNNER"), "operator"))
                    .isInstanceOf(BusinessException.class);
        }
        properties.setKagentRunnerImage(RUNNER.replace("eap-agent-runner", "eap-kagent-runner"));
        assertThat(service.capabilities().kagentRunnerAvailable()).isTrue();
        identity.setDeliveryPrivateKey("");
        assertThat(service.capabilities().kagentRunnerAvailable()).isFalse();
        assertThatThrownBy(() -> service.submit(7L, frameworkRunner("KAGENT_ADK", "RUNNER"), "operator"))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(client, owned, safety);
    }

    @Test void kagentFreezesImageAndUsesTheSameEapIdentityAndGatewayContract() {
        String image = RUNNER.replace("eap-agent-runner", "eap-kagent-runner");
        properties.setKagentRunnerImage(image);
        var first = service.submit(7L, frameworkRunner("KAGENT_ADK", "RUNNER"), "operator");
        assertThat(first.imageRef()).isEqualTo(image);
        assertThat(first.config()).containsEntry("runnerFramework", "KAGENT_ADK");
        properties.setKagentRunnerImage(RUNNER);
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c -> Optional.of(stored));
        service.processNext();
        var spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().image()).isEqualTo(image);
        assertThat(spec.getValue().projectIdentity()).isTrue();
        assertThat(spec.getValue().env()).containsEntry("EAP_IDENTITY_DIRECTORY", "/run/eap-identity")
                .containsEntry("AGENT_MODEL", "deepseek-flash")
                .doesNotContainKeys("EAP_TASK_TOKEN", "EAP_GATEWAY_TOKEN", "EAP_OBSERVABILITY_TOKEN");
    }

    @Test void kagentReplayKeepsOriginalReceiptAfterAvailabilityChangesAndRejectsFrameworkChange() {
        properties.setKagentRunnerImage(RUNNER);
        var request = frameworkRunner("KAGENT_ADK", "RUNNER");
        var first = service.submit(7L, request, "operator");
        when(repository.findByAgentIdAndSubmissionKey(7L, request.requestKey())).thenReturn(Optional.of(stored));
        properties.setKagentRunnerImage("");
        identity.setDeliveryPrivateKey("");
        clearInvocations(repository, client, owned, safety);
        var replay = service.submit(7L, request, "operator");
        assertThat(replay.deploymentKey()).isEqualTo(first.deploymentKey());
        assertThat(replay.imageRef()).isEqualTo(first.imageRef());
        assertThatThrownBy(() -> service.submit(7L, frameworkRunner("EAP_NATIVE", "RUNNER"), "operator"))
                .isInstanceOf(com.eap.platform.common.ConflictException.class);
        verify(repository, never()).save(any());
        verifyNoInteractions(client, owned, safety);
    }

    @Test void explicitNativeFrameworkPreservesLegacyIntentAndInvalidFrameworksCannotPublish() {
        var first = service.submit(7L, frameworkRunner(null, "RUNNER"), "operator");
        assertThat(first.config()).doesNotContainKey("runnerFramework");
        when(repository.findByAgentIdAndSubmissionKey(7L, "framework-test")).thenReturn(Optional.of(stored));
        assertThat(service.submit(7L, frameworkRunner("EAP_NATIVE", "RUNNER"), "operator").deploymentKey())
                .isEqualTo(first.deploymentKey());
        clearInvocations(repository, client, owned, safety);
        for (var request : List.of(frameworkRunner("UNKNOWN", "RUNNER"), frameworkRunner("KAGENT_ADK", "IMAGE"),
                frameworkRunner("KAGENT_ADK", "SOURCE"))) {
            assertThatThrownBy(() -> service.submit(7L, request, "operator")).isInstanceOf(BusinessException.class);
        }
        verify(repository, never()).save(any());
        verifyNoInteractions(client, owned, safety);
    }

    @Test void kagentToolModeIsFrozenAndInjectedWithoutChangingOldIntent() {
        properties.setKagentRunnerImage(RUNNER);
        var first = service.submit(7L, toolRunner("KAGENT_ADK", "RUNNER", "CALCULATE_SUM_V1"), "operator");
        assertThat(first.config()).containsEntry("runnerToolMode", "CALCULATE_SUM_V1");
        when(repository.findByAgentIdAndSubmissionKey(7L, "framework-test")).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> service.submit(7L, toolRunner("KAGENT_ADK", "RUNNER", "NONE"), "operator"))
                .isInstanceOf(com.eap.platform.common.ConflictException.class);
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c -> Optional.of(stored));
        service.processNext();
        var spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().env()).containsEntry("EAP_KAGENT_TOOL_MODE", "CALCULATE_SUM_V1")
                .containsEntry("EAP_TOOLS_ENABLED", "true");
        assertThat(spec.getValue().projectIdentity()).isTrue();
    }

    @Test void disabledKagentToolsKeepLegacyReplayAndInvalidToolsCannotPublish() {
        properties.setKagentRunnerImage(RUNNER);
        for (var request : List.of(toolRunner("EAP_NATIVE", "RUNNER", "CALCULATE_SUM_V1"),
                toolRunner("KAGENT_ADK", "IMAGE", "CALCULATE_SUM_V1"),
                toolRunner("KAGENT_ADK", "RUNNER", "SHELL"))) {
            assertThatThrownBy(() -> service.submit(7L, request, "operator")).isInstanceOf(BusinessException.class);
        }
        verify(repository, never()).save(any());
        var first = service.submit(7L, frameworkRunner("KAGENT_ADK", "RUNNER"), "operator");
        assertThat(first.config()).doesNotContainKey("runnerToolMode");
        when(repository.findByAgentIdAndSubmissionKey(7L, "framework-test")).thenReturn(Optional.of(stored));
        assertThat(service.submit(7L, toolRunner("KAGENT_ADK", "RUNNER", "NONE"), "operator").deploymentKey())
                .isEqualTo(first.deploymentKey());
    }

    private static SubstrateDeploymentDtos.DeployRequest toolRunner(String framework, String source, String mode) {
        return new SubstrateDeploymentDtos.DeployRequest(source, null, null, null, null, Map.of(), "test prompt",
                "deepseek-flash", "GVISOR", "SMALL", List.of(), "framework-test", null, null, null, null,
                null, null, framework, mode);
    }

    private static SubstrateDeploymentDtos.DeployRequest frameworkRunner(String framework, String source) {
        return new SubstrateDeploymentDtos.DeployRequest(source, null, null, null, null, Map.of(), "test prompt",
                "deepseek-flash", "GVISOR", "SMALL", List.of(), "framework-test", null, null, null, null,
                null, null, framework);
    }
    @Test void freezesExplicitModelControlsAndInjectsOnlyPlatformOwnedVariables() {
        var request = controlledRunner(4096, "DISABLED", "deepseek-flash", "RUNNER");
        var response = service.submit(7L, request, "operator");
        assertThat(response.config()).containsEntry("maxTokens", 4096).containsEntry("thinkingMode", "DISABLED");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c -> Optional.of(stored));
        service.processNext();
        var spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().env()).containsEntry("AGENT_MAX_TOKENS", "4096").containsEntry("AGENT_THINKING_MODE", "DISABLED");
    }

    @Test void legacyOmittedControlsKeepConfigAndIdempotencyUnchanged() {
        var request = controlledRunner(null, null, "deepseek-flash", "RUNNER");
        var first = service.submit(7L, request, "operator");
        assertThat(first.config()).doesNotContainKeys("maxTokens", "thinkingMode");
        when(repository.findByAgentIdAndSubmissionKey(7L, request.requestKey())).thenReturn(Optional.of(stored));
        assertThat(service.submit(7L, request, "operator").deploymentKey()).isEqualTo(first.deploymentKey());
    }

    @Test void rejectsUnsupportedModelControlsBeforeDeployment() {
        for (int tokens : new int[]{0, -1, 8193}) {
            assertThatThrownBy(() -> service.submit(7L, controlledRunner(tokens, "DISABLED", "deepseek-flash", "RUNNER"), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("输出上限");
        }
        assertThatThrownBy(() -> service.submit(7L, controlledRunner(1024, "DISABLED", "other-model", "RUNNER"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("关闭思考");
        assertThatThrownBy(() -> service.submit(7L, controlledRunner(1024, "invalid", "deepseek-flash", "RUNNER"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("思考模式");
        assertThatThrownBy(() -> service.submit(7L, controlledRunner(1024, "PROVIDER_DEFAULT", "other-model", "IMAGE"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅适用于");
        verifyNoInteractions(client, owned);
    }

    private SubstrateDeploymentDtos.DeployRequest controlledRunner(Integer tokens, String thinking, String model, String source) {
        return new SubstrateDeploymentDtos.DeployRequest(source, null, null, null, null, Map.of(), "test prompt", model,
                "GVISOR", "SMALL", List.of(), "model-control-test", null, null, null, null, tokens, thinking);
    }
    @Test void failedDeliveryNeverReportsReadyAndRetryKeepsOriginalInstance() {
        service.submit(7L,runner(Map.of(),List.of()),"operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c->Optional.of(stored));
        when(client.goldenStatus(any(),any())).thenReturn(new SubstrateClient.GoldenStatus(true,null));
        doThrow(new BusinessException("synthetic lost ack")).doNothing().when(owned).connect(any(),anyString(),anyString());
        service.processNext();service.processNext();service.processNext();service.processNext();
        assertThat(stored.status).isEqualTo("FAILED");String key=stored.instanceKey;
        when(repository.findByAgentIdAndVersion(7L,1)).thenReturn(Optional.of(stored));
        assertThat(service.retry(7L,1,"operator").status()).isEqualTo("CONNECTING_IDENTITY");service.processNext();
        assertThat(stored.status).isEqualTo("READY");assertThat(stored.instanceKey).isEqualTo(key);verify(owned,times(1)).create(any(),anyString(),any());
    }
    @Test void legacyTemplateKeepsOriginalCredentialContract() throws Exception {
        service.submit(7L,runner(Map.of(),List.of()),"operator");
        var json=new ObjectMapper();var config=json.readValue(stored.configJson,Map.class);config.remove("identityMode");config.remove("identityPublicKey");stored.configJson=json.writeValueAsString(config);
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c->Optional.of(stored));service.processNext();
        var spec=ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().projectIdentity()).isFalse();assertThat(spec.getValue().env()).containsEntry("EAP_TASK_TOKEN","task-token").containsEntry("EAP_GATEWAY_TOKEN","gateway-token");
    }

    @Test
    void secretLookingOrReservedEnvIsRejected() {
        assertThatThrownBy(() -> service.submit(7L, runner(Map.of("OPENAI_API_KEY", "sk-x"), List.of()), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("密钥");
        assertThatThrownBy(() -> service.submit(7L, runner(Map.of("EAP_TASK_TOKEN", "x"), List.of()), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("保留");
        verifyNoInteractions(client);
    }

    @Test
    void extraEgressNeedsGovernanceAndAskStopsTheDeployment() {
        when(safety.evaluate(any())).thenAnswer(call -> {
            SafetyDtos.SafetyEventRequest event = call.getArgument(0);
            return decision("egress_allow".equals(event.action()) ? SafetyDecision.ASK : SafetyDecision.ALLOW);
        });

        var response = service.submit(7L, runner(Map.of(), List.of("api.example.com")), "operator");

        assertThat(response.status()).isEqualTo("WAITING_APPROVAL");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(Optional.empty());
        service.processNext();
        verifyNoInteractions(client);
    }

    @Test
    void failedGoldenSnapshotFailsTheDeployment() {
        service.submit(7L, runner(Map.of(), List.of()), "operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));
        service.processNext();
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(false, "image pull failed"));

        service.processNext();

        assertThat(stored.status).isEqualTo("FAILED");
        assertThat(stored.reason).contains("image pull failed");
        verify(client, never()).createActor(any(), any());
    }

    @Test
    void failedInstanceStepRetriesFromThatStepWithoutRebuildingTheTemplate() {
        service.submit(7L, runner(Map.of(), List.of()), "operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        when(owned.create(any(),anyString(),any())).thenThrow(new SubstrateClient.SubstrateException("INVALID_ARGUMENT", "bad policy"))
                .thenReturn("eap-agents/a7-v1-123456789abc");
        service.processNext();
        service.processNext();
        service.processNext();
        assertThat(stored.status).isEqualTo("FAILED");
        verify(client,never()).deleteActor(any());
        when(repository.findByAgentIdAndVersion(7L, 1)).thenReturn(Optional.of(stored));

        assertThat(service.retry(7L, 1, "operator").status()).isEqualTo("CREATING_INSTANCE");
        service.processNext();

        assertThat(stored.status).isEqualTo("CONNECTING_IDENTITY");service.processNext();
        assertThat(stored.status).isEqualTo("READY");
        assertThat(stored.templateName).isEqualTo("eap-a7-v1");
        verify(client, times(1)).createTemplate(any());
        assertThatThrownBy(() -> service.retry(7L, 1, "operator")).isInstanceOf(BusinessException.class);
    }

    @Test
    void deletingAnInstanceRemovesTheActorAndItsRegistration() {
        com.eap.platform.registry.AgentRuntimeInstance runtime = mock(com.eap.platform.registry.AgentRuntimeInstance.class);
        when(runtime.getAgentId()).thenReturn(7L);
        when(runtime.getProtocol()).thenReturn("SUBSTRATE");
        when(runtime.getStatus()).thenReturn(com.eap.platform.registry.RuntimeStatus.ONLINE);
        when(runtime.getEndpoint()).thenReturn("substrate://eap-agents/a7-v1-abc123");
        when(runtime.getInstanceKey()).thenReturn("eap-agents/a7-v1-abc123");
        when(runtimes.findByInstanceKey("eap-agents/a7-v1-abc123")).thenReturn(Optional.of(runtime));

        service.deleteInstance(7L, "a7-v1-abc123", "operator");

        verify(client).deleteActor(new SubstrateActorRef("eap-agents", "a7-v1-abc123"));
        verify(runtimes).delete(runtime);
        assertThatThrownBy(() -> service.deleteInstance(8L, "a7-v1-abc123", "operator")).isInstanceOf(BusinessException.class);
    }

    @Test
    void sourceDeploymentBuildsFirstAndPinsThePushedDigest() {
        when(builder.enabled()).thenReturn(true);
        when(builder.repository(7L)).thenReturn("localhost:5001/eap-src-a7");
        when(builder.destination(7L, 1)).thenReturn("10.0.0.1:5001/eap-src-a7:v1");
        when(builder.status("eap-build-a7-v1")).thenReturn(new ImageBuilder.BuildStatus("MISSING", null, null),
                new ImageBuilder.BuildStatus("RUNNING", null, null),
                new ImageBuilder.BuildStatus("SUCCEEDED", "sha256:" + "c".repeat(64), null));

        var response = service.submit(7L, source("https://gitee.com/org/agent.git/", "release", "./agent/"), "operator");
        assertThat(response.status()).isEqualTo("BUILDING_IMAGE");
        assertThat(response.imageRef()).isNull();
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));

        service.processNext();
        ArgumentCaptor<ImageBuilder.BuildSpec> spec = ArgumentCaptor.forClass(ImageBuilder.BuildSpec.class);
        verify(builder).start(spec.capture());
        assertThat(spec.getValue().gitUrl()).isEqualTo("https://gitee.com/org/agent.git");
        assertThat(spec.getValue().gitRef()).isEqualTo("release");
        assertThat(spec.getValue().contextPath()).isEqualTo("agent");
        assertThat(spec.getValue().destination()).isEqualTo("10.0.0.1:5001/eap-src-a7:v1");
        service.processNext();
        assertThat(stored.status).isEqualTo("BUILDING_IMAGE");
        service.processNext();

        assertThat(stored.status).isEqualTo("QUEUED");
        assertThat(stored.imageRef).isEqualTo("localhost:5001/eap-src-a7@sha256:" + "c".repeat(64));
        verify(client, never()).createTemplate(any());
    }

    @Test
    void sourceInputsAreValidatedAndFailedBuildsRetryFromTheBuild() {
        when(builder.enabled()).thenReturn(true);
        for (String bad : new String[]{"http://gitee.com/a/b", "https://user:pw@gitee.com/a/b", "https://gitee.com/a/b?x=1", "git://gitee.com/a/b"}) {
            assertThatThrownBy(() -> service.submit(7L, source(bad, "main", ""), "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("https");
        }
        assertThatThrownBy(() -> service.submit(7L, source("https://gitee.com/a/b", "main", "../etc"), "operator"))
                .isInstanceOf(BusinessException.class);

        when(builder.status(any())).thenReturn(new ImageBuilder.BuildStatus("FAILED", null, "构建失败"));
        when(builder.logTail(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("step 1\nError: base image not found\n\nUsage:\n  executor [flags]\nUse \"executor [command] --help\" for more information about a command.\n");
        service.submit(7L, source("https://gitee.com/a/b", "main", ""), "operator");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(call -> Optional.of(stored));
        service.processNext();
        assertThat(stored.status).isEqualTo("FAILED");
        assertThat(stored.reason).contains("base image not found");
        when(repository.findByAgentIdAndVersion(7L, 1)).thenReturn(Optional.of(stored));

        assertThat(service.retry(7L, 1, "operator").status()).isEqualTo("BUILDING_IMAGE");
        verify(builder).delete("eap-build-a7-v1");
    }

    @Test
    void plainHttpGitIsOnlyAcceptedForRegisteredInternalHosts() {
        when(builder.enabled()).thenReturn(true);
        assertThatThrownBy(() -> service.submit(7L, source("http://git.eap-git.svc/a.git", "main", ""), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内部 Git");
        properties.setBuildHttpGitHosts("git.eap-git.svc");
        assertThat(service.submit(7L, source("http://git.eap-git.svc/a.git", "main", ""), "operator").status()).isEqualTo("BUILDING_IMAGE");
        assertThatThrownBy(() -> service.submit(7L, source("http://evil.example.com/a.git", "main", ""), "operator"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sourceBuildRequiresTheBuilder() {
        when(builder.enabled()).thenReturn(false);
        assertThatThrownBy(() -> service.submit(7L, source("https://gitee.com/a/b", "main", ""), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未启用");
    }

    @Test
    void foreignImagesMustBePinnedByDigest() {
        assertThatThrownBy(() -> service.resolveImage("docker.io/library/nginx:latest"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sha256");
        assertThat(service.resolveImage("ghcr.io/org/agent@sha256:" + "b".repeat(64))).endsWith("b".repeat(64));
    }

    @Test
    void newImageUsesBoundCreationAndAcknowledgementWithoutSharedCredentials() {
        workflow.setToken("");
        properties.setGatewayToken("");
        observation.setIngestionToken("");
        var published = service.submit(7L, image(80, "image-publication"), "operator");
        assertThat(published.config()).containsEntry("identityMode", "PER_INSTANCE_V1");
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c -> Optional.of(stored));
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        service.processNext();
        var spec = ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);
        verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().projectIdentity()).isTrue();
        assertThat(spec.getValue().env()).containsEntry("EAP_IDENTITY_PUBLIC_KEY", "synthetic-test-public")
                .doesNotContainKeys("EAP_TASK_TOKEN", "EAP_GATEWAY_TOKEN", "EAP_OBSERVABILITY_TOKEN");
        service.processNext();service.processNext();
        assertThat(stored.status).isEqualTo("CONNECTING_IDENTITY");
        identity.setDeliveryPublicKey("rotated-public-key");
        service.processNext();
        assertThat(stored.status).isEqualTo("READY");
        assertThat(stored.configJson).contains("synthetic-test-public").doesNotContain("rotated-public-key");
        verify(owned).create(eq(stored), eq(stored.deploymentKey.substring(4)), any());
        verify(owned).connect(eq(stored), eq(stored.deploymentKey.substring(4)), anyString());
        verify(client, never()).createActor(any(), any());
        verifyNoInteractions(registry);
    }

    @Test
    void newSourceCannotSkipIdentityAfterItsImageIsBuilt() {
        when(builder.enabled()).thenReturn(true);
        var published = service.submit(7L, source("https://git.example/agent.git", "main", ""), "operator");
        assertThat(published.config()).containsEntry("identityMode", "PER_INSTANCE_V1");
        // The builder's pinned result has already been persisted; continue the original intent.
        stored.imageRef = RUNNER;stored.status = "QUEUED";
        when(repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(any())).thenAnswer(c -> Optional.of(stored));
        when(client.goldenStatus(any(), any())).thenReturn(new SubstrateClient.GoldenStatus(true, null));
        doThrow(new BusinessException("synthetic lost acknowledgement")).doNothing()
                .when(owned).connect(any(), anyString(), anyString());
        service.processNext();service.processNext();service.processNext();service.processNext();
        assertThat(stored.status).isEqualTo("FAILED");String originalKey=stored.instanceKey;
        when(repository.findByAgentIdAndVersion(7L, 1)).thenReturn(Optional.of(stored));
        assertThat(service.retry(7L, 1, "operator").status()).isEqualTo("CONNECTING_IDENTITY");
        service.processNext();
        assertThat(stored.status).isEqualTo("READY");assertThat(stored.instanceKey).isEqualTo(originalKey);
        verify(owned, times(1)).create(any(), anyString(), any());
        verify(client, never()).createActor(any(), any());
        verifyNoInteractions(registry);
        var spec=ArgumentCaptor.forClass(SubstrateClient.TemplateSpec.class);verify(client).createTemplate(spec.capture());
        assertThat(spec.getValue().env()).doesNotContainKeys("EAP_TASK_TOKEN", "EAP_GATEWAY_TOKEN", "EAP_OBSERVABILITY_TOKEN");
    }

    @Test
    void bothCustomSourcesFailBeforeAnyNativeOrBuildSideEffectWithoutDeliveryConfiguration() {
        when(builder.enabled()).thenReturn(true);identity.setDeliveryPrivateKey("");
        for (var request : List.of(image(80, "image-no-delivery"), source("https://git.example/agent.git", "main", ""))) {
            assertThatThrownBy(() -> service.submit(7L, request, "operator"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("安全配送");
        }
        verify(repository, never()).save(any());verifyNoInteractions(client, owned, safety);
        verify(builder, never()).start(any());
    }

    @Test
    void customPortIsRejectedBeforeAnyNewPublicationSideEffect() {
        assertThatThrownBy(() -> service.submit(7L, image(8080, "wrong-port"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("端口 80");
        verify(repository, never()).save(any());verifyNoInteractions(client, owned, safety);
    }

    @Test
    void originalLegacyRequestCanBeReadWithoutMigrationOrCurrentDeliveryReadiness() throws Exception {
        service.submit(7L, image(80, "legacy-replay"), "operator");
        var mapper = new ObjectMapper();var config = mapper.readValue(stored.configJson, Map.class);
        config.remove("identityMode");config.remove("identityPublicKey");config.put("port", 8080);
        stored.configJson = mapper.writeValueAsString(config);stored.status = "READY";
        String originalConfig=stored.configJson, originalKey=stored.deploymentKey;
        when(repository.findByAgentIdAndSubmissionKey(7L, "legacy-replay")).thenReturn(Optional.of(stored));
        identity.setDeliveryPrivateKey("");workflow.setToken("");
        clearInvocations(repository, client, owned, safety);
        var replay = service.submit(7L, image(8080, "legacy-replay"), "operator");
        assertThat(replay.deploymentKey()).isEqualTo(originalKey);assertThat(replay.status()).isEqualTo("READY");
        assertThat(stored.configJson).isEqualTo(originalConfig);
        verify(repository, never()).save(any());verifyNoInteractions(client, owned, safety);
        assertThatThrownBy(() -> service.submit(7L, image(80, "legacy-replay"), "operator"))
                .isInstanceOf(com.eap.platform.common.ConflictException.class);
    }

    @Test
    void disabledDispatchStillBlocksNewSecurePublicationWithoutSharedTokens() {
        workflow.setToken("");workflow.setEnabled(false);
        assertThatThrownBy(() -> service.submit(7L, image(80, "disabled-dispatch"), "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务派发");
        verify(repository, never()).save(any());verifyNoInteractions(client, owned, safety);
    }

    private static SubstrateDeploymentDtos.DeployRequest image(int port, String key) {
        return new SubstrateDeploymentDtos.DeployRequest("IMAGE", RUNNER, List.of(), port, "/readyz", Map.of(),
                null, null, "GVISOR", "SMALL", List.of(), key);
    }

    private static SubstrateDeploymentDtos.DeployRequest source(String gitUrl, String gitRef, String contextPath) {
        return new SubstrateDeploymentDtos.DeployRequest("SOURCE", null, null, 80, "/healthz", Map.of(), null, null,
                "GVISOR", "SMALL", List.of(), null, gitUrl, gitRef, contextPath, null);
    }

    private static SubstrateDeploymentDtos.DeployRequest runner(Map<String, String> env, List<String> hosts) {
        return new SubstrateDeploymentDtos.DeployRequest("RUNNER", null, null, null, null, env,
                "你是企业助手", "deepseek-flash", "GVISOR", "SMALL", hosts, null);
    }

    private void allow(SafetyDecision value) {
        when(safety.evaluate(any())).thenReturn(decision(value));
    }

    private static SafetyDtos.SafetyEvaluationResponse decision(SafetyDecision value) {
        return new SafetyDtos.SafetyEvaluationResponse(null, new SafetyDtos.DecisionResponse(null, null, null, value,
                null, null, null, null, null, null));
    }
    @Test void chatTransmitsOnlyExplicitHistoryAndRequiresIsolationProtocol() throws Exception {
        for (boolean isolated : List.of(false,true)) {
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            var received=new java.util.concurrent.atomic.AtomicReference<String>();
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/eap/state",exchange->{
                byte[] body=(isolated ? "{\"conversationMode\":\"EXPLICIT_V1\"}" : "{\"turns\":8}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.createContext("/chat",exchange->{
                calls.incrementAndGet();received.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                byte[] body="{\"reply\":\"synthetic reply\",\"turns\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            server.start();
            try {
                properties.setRouterUrl("http://127.0.0.1:"+server.getAddress().getPort());
                var runtime=mock(com.eap.platform.registry.AgentRuntimeInstance.class);
                when(runtime.getAgentId()).thenReturn(7L);when(runtime.getProtocol()).thenReturn("SUBSTRATE");
                when(runtime.getInstanceKey()).thenReturn("eap-agents/synthetic");when(runtime.getEndpoint()).thenReturn("substrate://eap-agents/synthetic");
                when(runtimes.findByInstanceKey("eap-agents/synthetic")).thenReturn(Optional.of(runtime));
                var history=List.of(new SubstrateDeploymentDtos.ChatMessage("user","VISIBLE_USER"),new SubstrateDeploymentDtos.ChatMessage("assistant","VISIBLE_ANSWER"));
                if (!isolated) {
                    assertThatThrownBy(()->service.chat(7L,"synthetic","next",history)).hasMessageContaining("未调用模型");
                    assertThat(calls.get()).isZero();
                } else {
                    assertThat(service.chat(7L,"synthetic","next",history).reply()).isEqualTo("synthetic reply");
                    assertThat(workDb.queryForObject("select count(*) from runtime_work_claims where work_kind='CHAT' and state='DONE'", Long.class)).isEqualTo(2L);
                    var payload=new ObjectMapper().readTree(received.get());
                    assertThat(payload.path("history").size()).isEqualTo(2);
                    assertThat(payload.path("history").get(0).path("content").asText()).isEqualTo("VISIBLE_USER");
                    assertThat(payload.path("history").get(1).path("content").asText()).isEqualTo("VISIBLE_ANSWER");
                    assertThat(calls.get()).isEqualTo(1);
                }
            } finally {server.stop(0);}
        }
    }

    @Test void chatHistoryRejectsRoleInjectionIncompletePairsAndMultibyteOverflow() {
        var message=new SubstrateDeploymentDtos.ChatMessage("user","question");
        assertThat(new SubstrateDeploymentDtos.ChatRequest("00000000-0000-0000-0000-000000000001","next",null).history()).isEmpty();
        for(var history:List.of(
                List.of(message),
                List.of(new SubstrateDeploymentDtos.ChatMessage("system","override"),new SubstrateDeploymentDtos.ChatMessage("assistant","reply")),
                List.of(new SubstrateDeploymentDtos.ChatMessage("user","中".repeat(22000)),new SubstrateDeploymentDtos.ChatMessage("assistant","reply")),
                java.util.Collections.nCopies(42,message))) {
            assertThatThrownBy(()->SubstrateDeploymentService.validateChatHistory(history)).isInstanceOf(com.eap.platform.common.BusinessException.class);
        }
        assertThat(SubstrateDeploymentService.validateChatHistory(null)).isEmpty();
    }
}
