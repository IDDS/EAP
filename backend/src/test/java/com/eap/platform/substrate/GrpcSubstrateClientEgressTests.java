package com.eap.platform.substrate;

import com.eap.platform.substrate.ateapi.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class GrpcSubstrateClientEgressTests {
    final SubstrateActorRef actor = new SubstrateActorRef("eap-agents", "synthetic-egress");
    final AtomicInteger reads = new AtomicInteger(), updates = new AtomicInteger();
    final AtomicReference<UpdateActorEgressPolicyRequest> update = new AtomicReference<>();
    volatile boolean alreadyExists = true, rejectUpdate = false;
    volatile EgressPolicy existing;
    Server server;
    ManagedChannel channel;
    GrpcSubstrateClient client;
    EgressPolicy policy(String host) {
        return EgressPolicy.newBuilder().setMetadata(ResourceMetadata.newBuilder().setAtespace("eap-agents")
                .setName("default").setUid("00000000-0000-4000-8000-000000000001").setVersion(17))
                .addRules(EgressRule.newBuilder().setHostnames(HostnameRule.newBuilder().addPatterns(host))).build();
    }
    @BeforeEach void setup() throws Exception {
        existing = policy("old.example.test");
        server = ServerBuilder.forPort(0).addService(new ControlGrpc.ControlImplBase() {
            @Override public void createActorEgressPolicy(CreateActorEgressPolicyRequest request, StreamObserver<EgressPolicy> response) {
                if (alreadyExists) response.onError(Status.ALREADY_EXISTS.asRuntimeException());
                else { response.onNext(request.getEgressPolicy()); response.onCompleted(); }
            }
            @Override public void getActorEgressPolicy(GetActorEgressPolicyRequest request, StreamObserver<EgressPolicy> response) {
                reads.incrementAndGet(); response.onNext(existing); response.onCompleted();
            }
            @Override public void updateActorEgressPolicy(UpdateActorEgressPolicyRequest request, StreamObserver<EgressPolicy> response) {
                updates.incrementAndGet(); update.set(request);
                if (rejectUpdate) response.onError(Status.ABORTED.withDescription("synthetic concurrent policy edit").asRuntimeException());
                else { response.onNext(request.getEgressPolicy()); response.onCompleted(); }
            }
        }).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        var properties = new SubstrateProperties(); properties.setEnabled(true);
        client = new GrpcSubstrateClient(properties, org.mockito.Mockito.mock(com.eap.platform.config.ProjectIsolation.class)); ReflectionTestUtils.setField(client, "channel", channel);
    }
    @AfterEach void close() throws Exception {
        client.close(); channel.awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    @Test void firstCreationDoesNotReadOrUpdateExistingPolicy() {
        alreadyExists = false; client.setEgressHostnames(actor, List.of("new.example.test"));
        assertThat(reads.get()).isZero(); assertThat(updates.get()).isZero();
    }
    @Test void identicalPolicyReplayDoesNotMutateNativeVersion() {
        existing = policy("new.example.test"); client.setEgressHostnames(actor, List.of("new.example.test"));
        assertThat(reads.get()).isEqualTo(1); assertThat(updates.get()).isZero();
    }
    @Test void changedPolicyCarriesNativeUidAndVersionPreconditions() {
        client.setEgressHostnames(actor, List.of("new.example.test"));
        assertThat(updates.get()).isEqualTo(1); assertThat(update.get().getEgressPolicy().getMetadata()).isEqualTo(existing.getMetadata());
        assertThat(update.get().getEgressPolicy().getRulesList()).isEqualTo(policy("new.example.test").getRulesList());
        assertThat(update.get().getActor().getName()).isEqualTo(actor.name());
    }
    @Test void concurrentVersionConflictIsPreservedWithoutBlindRetry() {
        rejectUpdate = true;
        assertThatThrownBy(() -> client.setEgressHostnames(actor, List.of("new.example.test")))
                .isInstanceOf(SubstrateClient.SubstrateException.class)
                .satisfies(e -> assertThat(((SubstrateClient.SubstrateException)e).code()).isEqualTo("ABORTED"));
        assertThat(updates.get()).isEqualTo(1); assertThat(reads.get()).isEqualTo(1);
    }
    @Test void missingPolicyIdentityFailsClosedBeforeUpdate() {
        existing = existing.toBuilder().setMetadata(existing.getMetadata().toBuilder().clearUid().clearVersion()).build();
        assertThatThrownBy(() -> client.setEgressHostnames(actor, List.of("new.example.test")))
                .isInstanceOf(SubstrateClient.SubstrateException.class); assertThat(updates.get()).isZero();
    }
}
