package com.eap.platform.substrate;

import com.eap.platform.substrate.ateapi.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GrpcSubstrateClientPlacementTests {
    static final String ACTOR_UID = "00000000-0000-4000-8000-000000000001";
    static final String WORKER_UID = "00000000-0000-4000-8000-000000000002";
    static final String POD_UID = "00000000-0000-4000-8000-000000000003";
    final SubstrateActorRef ref = new SubstrateActorRef("eap-agents", "placement-probe");
    final AtomicInteger actorReads = new AtomicInteger(), workerReads = new AtomicInteger();
    volatile Actor actor, after;
    volatile Worker worker;
    volatile Status workerFailure;
    Server server;
    ManagedChannel channel;
    GrpcSubstrateClient client;

    @BeforeEach void setup() throws Exception {
        var assignment = WorkerAssignment.newBuilder().setWorker(ObjectRef.newBuilder().setName("opaque-worker"))
                .setWorkerNamespace("eap-agents").setWorkerPool("pool").setWorkerPod("pod")
                .setWorkerPodUid(POD_UID).setWorkerPodIp("192.0.2.1").setNodeName("node-a");
        actor = Actor.newBuilder().setMetadata(ResourceMetadata.newBuilder().setAtespace(ref.atespace())
                        .setName(ref.name()).setUid(ACTOR_UID).setVersion(7))
                .setActorTemplate(ObjectRef.newBuilder().setAtespace(ref.atespace()).setName("template"))
                .setStatus(ActorStatus.newBuilder().setState(com.eap.platform.substrate.ateapi.ActorState.ACTOR_STATE_RUNNING)
                        .setWorkerAssignment(assignment)).build();
        worker = Worker.newBuilder().setMetadata(ResourceMetadata.newBuilder().setName("opaque-worker")
                        .setUid(WORKER_UID).setVersion(2))
                .setWorkerNamespace("eap-agents").setWorkerPool("pool").setWorkerPod("pod")
                .setWorkerPodUid(POD_UID).setNodeName("node-a").setIp("192.0.2.1").build();
        server = ServerBuilder.forPort(0).addService(new ControlGrpc.ControlImplBase() {
            @Override public void getActor(GetActorRequest request, StreamObserver<Actor> response) {
                assertThat(request.getActor().getName()).isEqualTo(ref.name());
                int count = actorReads.incrementAndGet();
                response.onNext(count > 1 && after != null ? after : actor); response.onCompleted();
            }
            @Override public void getWorker(GetWorkerRequest request, StreamObserver<Worker> response) {
                workerReads.incrementAndGet();
                assertThat(request.getWorker().getName()).isEqualTo("opaque-worker");
                assertThat(request.getWorker().getAtespace()).isEmpty();
                if (workerFailure != null) response.onError(workerFailure.asRuntimeException());
                else { response.onNext(worker); response.onCompleted(); }
            }
        }).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        var properties = new SubstrateProperties(); properties.setEnabled(true);
        client = new GrpcSubstrateClient(properties); ReflectionTestUtils.setField(client, "channel", channel);
    }
    @AfterEach void close() throws Exception {
        client.close(); channel.awaitTermination(5, TimeUnit.SECONDS); server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    @Test void verifiesOpaqueWorkerReferenceAndReturnsOnlySafeCoordinates() {
        var value = client.locate(ref);
        assertThat(value.actor().uid()).isEqualTo(ACTOR_UID);
        assertThat(value.actorVersion()).isEqualTo(7);
        assertThat(value.placement().workerUid()).isEqualTo(WORKER_UID);
        assertThat(value.placement().podUid()).isEqualTo(POD_UID);
        assertThat(value.placement().nodeName()).isEqualTo("node-a");
        assertThat(value.toString()).doesNotContain("192.0.2.1");
        assertThat(actorReads.get()).isEqualTo(2); assertThat(workerReads.get()).isEqualTo(1);
    }
    @Test void suspendedActorDoesNotUseAStaleAssignmentOrQueryAWorker() {
        actor = actor.toBuilder().setStatus(actor.getStatus().toBuilder()
                .setState(com.eap.platform.substrate.ateapi.ActorState.ACTOR_STATE_SUSPENDED)).build();
        assertThat(client.locate(ref).placement()).isNull();
        assertThat(actorReads.get()).isEqualTo(1); assertThat(workerReads.get()).isZero();
    }
    @Test void runningWithoutAssignmentDoesNotInventCoordinates() {
        actor = actor.toBuilder().setStatus(actor.getStatus().toBuilder().clearWorkerAssignment()).build();
        assertThat(client.locate(ref).placement()).isNull(); assertThat(workerReads.get()).isZero();
    }
    @Test void replacedWorkerPodFailsInsteadOfJoiningTwoIdentities() {
        worker = worker.toBuilder().setWorkerPodUid(ACTOR_UID).build();
        assertCode("DATA_LOSS"); assertThat(workerReads.get()).isEqualTo(1);
    }
    @Test void sameNameActorReplacementDuringTheReadIsNotAValidSnapshot() {
        after = actor.toBuilder().setMetadata(actor.getMetadata().toBuilder().setUid(WORKER_UID)).build();
        assertCode("ABORTED"); assertThat(actorReads.get()).isEqualTo(2);
    }
    @Test void assignmentChangeDuringReadIsNotRetriedOrReturnedAsCurrent() {
        after = actor.toBuilder().setStatus(actor.getStatus().toBuilder().setWorkerAssignment(
                actor.getStatus().getWorkerAssignment().toBuilder().setNodeName("node-b"))).build();
        assertCode("ABORTED"); assertThat(actorReads.get()).isEqualTo(2); assertThat(workerReads.get()).isEqualTo(1);
    }
    @Test void workerFailureIsPreservedWithoutLifecycleOrRetryCalls() {
        workerFailure = Status.PERMISSION_DENIED;
        assertCode("PERMISSION_DENIED"); assertThat(actorReads.get()).isEqualTo(1); assertThat(workerReads.get()).isEqualTo(1);
    }
    private void assertCode(String code) {
        assertThatThrownBy(() -> client.locate(ref)).isInstanceOf(SubstrateClient.SubstrateException.class)
                .satisfies(e -> assertThat(((SubstrateClient.SubstrateException)e).code()).isEqualTo(code));
    }
}
