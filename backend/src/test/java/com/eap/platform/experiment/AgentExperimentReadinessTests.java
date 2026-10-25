package com.eap.platform.experiment;

import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeConnectionService;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentExperimentReadinessTests {
    HttpServer server;
    SubstrateClient nativeClient;
    AgentExperimentClient client;
    AgentRuntimeInstanceRepository runtimes;
    RuntimeConnectionService connections;
    AtomicInteger profileRequests = new AtomicInteger(), modelDispatches = new AtomicInteger();
    int profileStatus = 200;
    String profileBody;
    String digest = "a".repeat(64);

    @BeforeEach void setup() throws Exception {
        var json = new ObjectMapper();
        profileBody = json.writeValueAsString(Map.of("protocol", "EAP_STATELESS_PROMPT_V1",
                "profileDigest", digest, "systemPrompt", "Public fixture", "model", "fixture",
                "context", "EMPTY", "tools", "DISABLED"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/eap/experiments/profile", exchange -> {
            profileRequests.incrementAndGet();
            byte[] raw = profileBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(profileStatus, raw.length);
            exchange.getResponseBody().write(raw);exchange.close();
        });
        server.createContext("/eap/experiments/run", exchange -> {
            modelDispatches.incrementAndGet();
            byte[] raw = "{\"status\":\"SUCCEEDED\",\"traceId\":\"trace-fixture\",\"output\":\"\",\"finishReason\":\"length\"}".getBytes();
            exchange.sendResponseHeaders(200, raw.length);exchange.getResponseBody().write(raw);exchange.close();
        });
        server.start();
        var substrate = new SubstrateProperties();substrate.setEnabled(true);
        substrate.setRouterUrl("http://127.0.0.1:" + server.getAddress().getPort());
        var workflow = new WorkflowExecutionProperties();workflow.setEnabled(true);
        workflow.setToken("synthetic-test-only");workflow.setAllowedHosts("127.0.0.1");
        nativeClient = mock(SubstrateClient.class);
        when(nativeClient.resume(any())).thenReturn(new SubstrateClient.ActorState("RUNNING"));
        when(nativeClient.inspect(any())).thenReturn(new SubstrateClient.ActorInventory(
                "eap-agents", "fixture", "fixture-uid", "eap-agents/template", "RUNNING"));
        runtimes = mock(AgentRuntimeInstanceRepository.class);
        when(runtimes.findByInstanceKey(any())).thenReturn(Optional.empty());
        connections = mock(RuntimeConnectionService.class);
        var work = mock(com.eap.platform.registry.RuntimeWorkService.class);
        when(work.perform(anyString(), anyString(), any(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(2)).get());
        client = new AgentExperimentClient(substrate, nativeClient, workflow, json, runtimes, connections, work);
    }
    @AfterEach void close() { server.stop(0); }
    void installedIdentity() {
        var runtime=mock(com.eap.platform.registry.AgentRuntimeInstance.class);
        when(runtime.hasBoundIdentity()).thenReturn(true);
        when(runtimes.findByInstanceKey(any())).thenReturn(Optional.of(runtime));
        when(connections.executable(runtime)).thenReturn(true);
        when(connections.inspectionToken(runtime)).thenReturn("synthetic-installed-credential");
    }
    @Test void readOnlyInspectionUsesInstalledCredentialAndNeverResumesRenewsOrDispatches() {
        installedIdentity();var result=client.inspectProfile("substrate://eap-agents/fixture");
        assertThat(result.actorUid()).isEqualTo("fixture-uid");assertThat(profileRequests.get()).isEqualTo(1);assertThat(modelDispatches.get()).isZero();
        verify(nativeClient,never()).resume(any());verify(connections,never()).dispatchToken(any());verify(connections).inspectionToken(any());verify(connections,times(2)).checkIdentity(any());
    }
    @Test void readOnlyInspectionRejectsSharedCredentialFallbackWithoutHttpRequest() {
        assertThatThrownBy(()->client.inspectProfile("substrate://eap-agents/fixture")).hasMessage("实验运行准备未确认，请按诊断码核对原实例");
        assertThat(profileRequests.get()).isZero();verify(nativeClient,never()).resume(any());verifyNoInteractions(connections);
    }
    @Test void readOnlyInspectionDoesNotWakeSuspendedActor() {
        installedIdentity();when(nativeClient.inspect(any())).thenReturn(new SubstrateClient.ActorInventory("eap-agents","fixture","fixture-uid","eap-agents/template","SUSPENDED"));
        assertThatThrownBy(()->client.inspectProfile("substrate://eap-agents/fixture")).hasMessage("实验运行准备未确认，请按诊断码核对原实例");
        assertThat(profileRequests.get()).isZero();verify(nativeClient,never()).resume(any());verify(connections,never()).inspectionToken(any());
    }
    @Test void disappearingIdentityCannotFallBackToSharedCredentialAtHttpSend() {
        installedIdentity();var installed=runtimes.findByInstanceKey("eap-agents/fixture");
        when(runtimes.findByInstanceKey(any())).thenReturn(installed,Optional.empty());
        assertThatThrownBy(()->client.inspectProfile("substrate://eap-agents/fixture")).hasMessage("实验运行准备未确认，请按诊断码核对原实例");
        assertThat(profileRequests.get()).isZero();assertThat(modelDispatches.get()).isZero();
        verify(nativeClient,never()).resume(any());verify(connections,never()).dispatchToken(any());verify(connections,never()).inspectionToken(any());
    }
    @Test void preparedStatelessTeamSendUsesOriginalInstalledIdentityAndNeverResumes() {
        installedIdentity();var d=new AgentExperimentClient.StatelessDispatch("substrate://eap-agents/fixture","fixture-uid","eap-agents/template",digest,Map.of("traceId","trace-fixture"),java.time.Instant.now().plusSeconds(30));
        assertThat(client.executeStateless(d).status()).isEqualTo("SUCCEEDED");assertThat(modelDispatches.get()).isEqualTo(1);verify(nativeClient,never()).resume(any());verify(connections,never()).dispatchToken(any());
    }
    @Test void preparedStatelessTeamSendRejectsExpiredDeadlineAndEncodedBodyOverflow() {
        installedIdentity();var d=new AgentExperimentClient.StatelessDispatch("substrate://eap-agents/fixture","fixture-uid","eap-agents/template",digest,Map.of("traceId","trace-fixture"),java.time.Instant.now().minusSeconds(1));
        assertThat(client.executeStateless(d).status()).isEqualTo("FAILED");
        var oversized=new AgentExperimentClient.StatelessDispatch(d.endpoint(),d.actorUid(),d.template(),d.profileDigest(),Map.of("traceId","trace-fixture","systemPrompt","\u0001".repeat(8000)),java.time.Instant.now().plusSeconds(30));
        assertThat(client.executeStateless(oversized).reasonCode()).isEqualTo("RUNNER_BODY_LIMIT");assertThat(modelDispatches.get()).isZero();
    }
    AgentExperimentService.Dispatch dispatch() {
        return new AgentExperimentService.Dispatch(1L,1L,"eap-agents/fixture","substrate://eap-agents/fixture",
                "fixture-uid","eap-agents/template",digest,Map.of("traceId","trace-fixture"));
    }
    @ParameterizedTest @CsvSource({"401,RUNTIME_PROFILE_HTTP_401", "503,RUNTIME_PROFILE_HTTP_503"})
    void profileHttpFailureIsDistinctAndNeverDispatchesOrRetries(int status, String reason) {
        profileStatus = status;profileBody = "private response body must not become a diagnostic";
        var result = client.execute(dispatch());
        assertThat(result.status()).isEqualTo("FAILED");assertThat(result.reasonCode()).isEqualTo(reason);
        assertThat(result.body()).isNull();assertThat(profileRequests.get()).isEqualTo(1);
        assertThat(modelDispatches.get()).isZero();
    }
    @ParameterizedTest @CsvSource({"RESUME,UNAVAILABLE,RUNTIME_RESUME_UNAVAILABLE", "RESUME,RESOURCE_EXHAUSTED,RUNTIME_RESUME_RESOURCE_EXHAUSTED", "INSPECT,DEADLINE_EXCEEDED,RUNTIME_INSPECT_DEADLINE_EXCEEDED"})
    void nativeFailureRetainsOnlyWhitelistedStageAndCode(String stage,String code,String reason) {
        var failure = new SubstrateClient.SubstrateException(code,"private service text must not be retained");
        if (stage.equals("RESUME")) when(nativeClient.resume(any())).thenThrow(failure);
        else when(nativeClient.inspect(any())).thenThrow(failure);
        var result = client.execute(dispatch());
        assertThat(result.status()).isEqualTo("FAILED");assertThat(result.reasonCode()).isEqualTo(reason);
        assertThat(result.body()).isNull();assertThat(profileRequests.get()).isZero();
        assertThat(modelDispatches.get()).isZero();verify(nativeClient,times(1)).resume(any());
    }
    @Test void arbitraryNativeCodeCannotEnterStoredDiagnostic() {
        when(nativeClient.resume(any())).thenThrow(new SubstrateClient.SubstrateException("untrusted-message","private text"));
        assertThat(client.execute(dispatch()).reasonCode()).isEqualTo("RUNTIME_RESUME_UNCONFIRMED");
        assertThat(profileRequests.get()).isZero();assertThat(modelDispatches.get()).isZero();
    }
    @Test void malformedProfileIsKnownPreparationFailureWithNoBusinessDispatch() {
        profileBody = "not JSON";
        assertThat(client.execute(dispatch()).reasonCode()).isEqualTo("RUNTIME_PROFILE_INVALID");
        assertThat(profileRequests.get()).isEqualTo(1);assertThat(modelDispatches.get()).isZero();
    }
    @Test void successfulPreflightStillDispatchesExactlyOnceAndPreservesEmptyOutput() {
        var result = client.execute(dispatch());
        assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.reasonCode()).isNull();
        assertThat(result.body().path("output").isTextual()).isTrue();
        assertThat(result.body().path("output").asText()).isEmpty();
        assertThat(profileRequests.get()).isEqualTo(1);assertThat(modelDispatches.get()).isEqualTo(1);
    }
}
