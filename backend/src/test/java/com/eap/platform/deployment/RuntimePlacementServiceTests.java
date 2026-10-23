package com.eap.platform.deployment;

import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.*;
import com.eap.platform.substrate.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimePlacementServiceTests {
    static final String UID = "00000000-0000-4000-8000-000000000001", KEY = "eap-agents/probe";
    final AgentRuntimeInstanceRepository runtimes = mock(AgentRuntimeInstanceRepository.class);
    final AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
    final SubstrateClient client = mock(SubstrateClient.class);
    final RuntimePlacementService service = new RuntimePlacementService(runtimes, client);
    final SubstrateActorRef ref = new SubstrateActorRef("eap-agents", "probe");
    final SubstrateClient.WorkerPlacement placement = new SubstrateClient.WorkerPlacement(
            "worker", UID, "eap-agents", "pool", "pod", UID, "node-a");
    @BeforeEach void setup() {
        when(runtimes.findByInstanceKey(KEY)).thenReturn(Optional.of(runtime));
        when(runtime.getAgentId()).thenReturn(7L); when(runtime.getProtocol()).thenReturn("SUBSTRATE");
        when(runtime.getStatus()).thenReturn(RuntimeStatus.ONLINE); when(runtime.getEndpoint()).thenReturn("substrate://" + KEY);
        when(runtime.getBoundActorUid()).thenReturn(UID); when(runtime.getBoundActorTemplate()).thenReturn("eap-agents/template");
        when(client.locate(ref)).thenReturn(location(UID, "RUNNING", placement));
    }
    SubstrateClient.ActorLocation location(String uid, String state, SubstrateClient.WorkerPlacement p) {
        return new SubstrateClient.ActorLocation(new SubstrateClient.ActorInventory("eap-agents", "probe", uid,
                "eap-agents/template", state), 9, p);
    }
    @Test void assignedEvidenceIsBoundToTheRegistryIdentityAndReadWindow() {
        Instant before = Instant.now(); var value = service.current(7L, KEY);
        assertThat(value.status()).isEqualTo("ASSIGNED"); assertThat(value.source()).isEqualTo("SUBSTRATE_CONTROL_API");
        assertThat(value.actorUid()).isEqualTo(UID); assertThat(value.actorVersion()).isEqualTo(9);
        assertThat(value.placement()).isEqualTo(placement);
        assertThat(value.observationStartedAt()).isAfterOrEqualTo(before);
        assertThat(value.observedAt()).isAfterOrEqualTo(value.observationStartedAt());
        verify(client).locate(ref); verifyNoMoreInteractions(client);
        verify(runtimes).findByInstanceKey(KEY); verifyNoMoreInteractions(runtimes);
    }
    @Test void anotherAgentCannotInspectTheRuntime() {
        assertThatThrownBy(() -> service.current(8L, KEY)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }
    @Test void delayedCaptureRejectsAReplacedOriginalBindingBeforeProbing() {
        assertThat(service.currentForBinding(7L, KEY, UID, "eap-agents/template").status()).isEqualTo("ASSIGNED");
        clearInvocations(client);
        assertThat(service.currentForBinding(7L, KEY, UID, "old-template").reason()).isEqualTo("ORIGINAL_IDENTITY_MISMATCH");
        assertThat(service.currentForBinding(7L, KEY, "old-uid", "eap-agents/template").reason()).isEqualTo("ORIGINAL_IDENTITY_MISMATCH");
        assertThat(service.currentForBinding(7L, KEY, null, "old-template").reason()).isEqualTo("IDENTITY_UNBOUND");
        verifyNoInteractions(client);
    }
    @Test void sameNameReplacementDoesNotLeakItsLocationOrIdentity() {
        when(client.locate(ref)).thenReturn(location("00000000-0000-4000-8000-000000000002", "RUNNING", placement));
        var value = service.current(7L, KEY);
        assertThat(value.reason()).isEqualTo("IDENTITY_MISMATCH"); assertThat(value.placement()).isNull();
        assertThat(value.actorUid()).isNull(); assertThat(value.source()).isNull();
    }
    @Test void differentTemplateOrActorReferenceCannotBorrowTheBinding() {
        for (var actor : new SubstrateClient.ActorInventory[]{
                new SubstrateClient.ActorInventory("other-space", "probe", UID, "eap-agents/template", "RUNNING"),
                new SubstrateClient.ActorInventory("eap-agents", "other-actor", UID, "eap-agents/template", "RUNNING"),
                new SubstrateClient.ActorInventory("eap-agents", "probe", UID, "eap-agents/other", "RUNNING")}) {
            when(client.locate(ref)).thenReturn(new SubstrateClient.ActorLocation(actor, 9, placement));
            assertThat(service.current(7L, KEY).reason()).isEqualTo("IDENTITY_MISMATCH");
        }
    }
    @Test void suspensionClearsCurrentLocationRatherThanReusingPreviousNode() {
        assertThat(service.current(7L, KEY).status()).isEqualTo("ASSIGNED");
        when(client.locate(ref)).thenReturn(location(UID, "SUSPENDED", placement));
        var value = service.current(7L, KEY);
        assertThat(value.status()).isEqualTo("UNASSIGNED"); assertThat(value.placement()).isNull();
        assertThat(value.actorUid()).isEqualTo(UID);
    }
    @Test void offlineAndUnboundRuntimesAreNotProbedOrWoken() {
        when(runtime.getStatus()).thenReturn(RuntimeStatus.OFFLINE);
        assertThat(service.current(7L, KEY).reason()).isEqualTo("INSTANCE_OFFLINE");
        when(runtime.getStatus()).thenReturn(RuntimeStatus.ONLINE); when(runtime.getBoundActorUid()).thenReturn(null);
        assertThat(service.current(7L, KEY).reason()).isEqualTo("IDENTITY_UNBOUND"); verifyNoInteractions(client);
    }
    @Test void missingOrMalformedCoordinatesCannotYieldAssignedStatus() {
        when(client.locate(ref)).thenReturn(location(UID, "RUNNING", null));
        assertThat(service.current(7L, KEY).reason()).isEqualTo("NO_WORKER_ASSIGNMENT");
        when(client.locate(ref)).thenReturn(location(UID, "RUNNING", new SubstrateClient.WorkerPlacement(
                "worker", UID, "namespace", "pool", "pod", UID, "private\nmessage")));
        var value = service.current(7L, KEY);
        assertThat(value.status()).isEqualTo("UNKNOWN"); assertThat(value.toString()).doesNotContain("private");
        when(client.locate(ref)).thenReturn(location(UID, "RESUMING", placement));
        assertThat(service.current(7L, KEY).reason()).isEqualTo("ACTOR_TRANSITIONING");
    }
    @Test void upstreamFailureDoesNotExposeTheRawErrorOrRetainOldLocation() {
        assertThat(service.current(7L, KEY).status()).isEqualTo("ASSIGNED");
        when(client.locate(ref)).thenThrow(new SubstrateClient.SubstrateException("UNAVAILABLE", "private upstream text"));
        var value = service.current(7L, KEY);
        assertThat(value.reason()).isEqualTo("CONTROL_API_UNAVAILABLE"); assertThat(value.placement()).isNull();
        assertThat(value.toString()).doesNotContain("private upstream text");
    }
}
