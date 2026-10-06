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
    private SubstrateDeploymentService service;
    private SubstrateDeployment stored;
    private final PlatformRuntimeCreationService owned=mock(PlatformRuntimeCreationService.class);
    private final com.eap.platform.registry.RuntimeConnectionProperties identity=new com.eap.platform.registry.RuntimeConnectionProperties();

    @BeforeEach
    void setUp() {
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
                properties, workflow, observation, safety, mock(AuditLogRepository.class), builder, new ObjectMapper().registerModule(new JavaTimeModule()), transactions,owned,identity,mock(com.eap.platform.registry.RuntimeConnectionService.class));
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

    private static SubstrateDeploymentDtos.DeployRequest source(String gitUrl, String gitRef, String contextPath) {
        return new SubstrateDeploymentDtos.DeployRequest("SOURCE", null, null, 8080, "/healthz", Map.of(), null, null,
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
}
