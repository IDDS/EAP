package com.eap.platform.substrate;

import com.eap.platform.substrate.ateapi.*;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Control API client authenticated with a projected Kubernetes service account token.
 * The token file is re-read on every call because kubelet rotates it; the server certificate
 * is verified against the Substrate service DNS trust bundle projected next to it.
 */
@Component
public class GrpcSubstrateClient implements SubstrateClient {
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final Logger log = LoggerFactory.getLogger(GrpcSubstrateClient.class);

    private final SubstrateProperties properties;
    private ManagedChannel channel;

    public GrpcSubstrateClient(SubstrateProperties properties) {
        this.properties = properties;
    }

    @Override
    public ActorState get(SubstrateActorRef actor) {
        return call(stub -> stub.getActor(GetActorRequest.newBuilder().setActor(ref(actor)).build()));
    }

    @Override
    public ActorInventory inspect(SubstrateActorRef actor) {
        return inventory(rpc(stub -> stub.getActor(GetActorRequest.newBuilder().setActor(ref(actor)).build())));
    }

    @Override
    public ActorLocation locate(SubstrateActorRef actor) {
        return rpc(stub -> {
            // One total deadline for all reads, with no retries or lifecycle operations.
            var read = stub.withDeadline(io.grpc.Deadline.after(
                    Math.min(5000, properties.getCallTimeoutMs()), TimeUnit.MILLISECONDS));
            var request = GetActorRequest.newBuilder().setActor(ref(actor)).build();
            Actor first = read.getActor(request);
            var status = first.getStatus();
            if (status.getState() != com.eap.platform.substrate.ateapi.ActorState.ACTOR_STATE_RUNNING
                    || !status.hasWorkerAssignment()) {
                return new ActorLocation(inventory(first), first.getMetadata().getVersion(), null);
            }
            var assignment = status.getWorkerAssignment();
            if (assignment.getWorker().getName().isBlank() || !assignment.getWorker().getAtespace().isEmpty()) {
                throw new SubstrateException("DATA_LOSS", "实例位置元数据不完整");
            }
            Worker worker = read.getWorker(GetWorkerRequest.newBuilder().setWorker(assignment.getWorker()).build());
            if (!worker.getMetadata().getAtespace().isEmpty()
                    || !worker.getMetadata().getName().equals(assignment.getWorker().getName())
                    || !worker.getWorkerNamespace().equals(assignment.getWorkerNamespace())
                    || !worker.getWorkerPool().equals(assignment.getWorkerPool())
                    || !worker.getWorkerPod().equals(assignment.getWorkerPod())
                    || !worker.getWorkerPodUid().equals(assignment.getWorkerPodUid())
                    || !worker.getNodeName().equals(assignment.getNodeName())) {
                throw new SubstrateException("DATA_LOSS", "Worker 与实例分配不一致");
            }
            Actor last = read.getActor(request);
            if (!first.getMetadata().equals(last.getMetadata())
                    || !first.getActorTemplate().equals(last.getActorTemplate())
                    || !status.equals(last.getStatus())) {
                throw new SubstrateException("ABORTED", "查询期间实例分配发生变化");
            }
            return new ActorLocation(inventory(first), first.getMetadata().getVersion(), new WorkerPlacement(
                    worker.getMetadata().getName(), worker.getMetadata().getUid(), worker.getWorkerNamespace(),
                    worker.getWorkerPool(), worker.getWorkerPod(), worker.getWorkerPodUid(), worker.getNodeName()));
        });
    }

    @Override
    public ActorPage listActors(String atespace, String pageToken) {
        if (atespace == null || atespace.isBlank()) throw new IllegalArgumentException("运行空间不可为空");
        var page = rpc(stub -> stub.withDeadlineAfter(5, TimeUnit.SECONDS).listActors(ListActorsRequest.newBuilder()
                .setAtespace(atespace).setPageSize(100).setPageToken(pageToken).build()));
        return new ActorPage(page.getActorsList().stream().map(GrpcSubstrateClient::inventory).toList(), page.getNextPageToken());
    }

    private static ActorInventory inventory(Actor actor) {
        var meta = actor.getMetadata();
        return new ActorInventory(meta.getAtespace(), meta.getName(), meta.getUid(),
                actor.getActorTemplate().getAtespace() + "/" + actor.getActorTemplate().getName(),
                actor.getStatus().getState().name().replaceFirst("^ACTOR_STATE_", ""));
    }

    @Override
    public ActorState suspend(SubstrateActorRef actor) {
        return call(stub -> stub.suspendActor(SuspendActorRequest.newBuilder().setActor(ref(actor)).build()).getActor());
    }

    @Override
    public ActorState resume(SubstrateActorRef actor) {
        return call(stub -> stub.resumeActor(ResumeActorRequest.newBuilder().setActor(ref(actor)).build()).getActor());
    }

    @Override
    public ActorState revert(SubstrateActorRef actor) {
        return call(stub -> stub.revertActor(RevertActorRequest.newBuilder().setActor(ref(actor)).build()).getActor());
    }

    @Override
    public void ensureAtespace(String atespace) {
        try {
            rpc(stub -> stub.createAtespace(CreateAtespaceRequest.newBuilder()
                    .setAtespace(Atespace.newBuilder().setMetadata(ResourceMetadata.newBuilder().setName(atespace))).build()));
        } catch (SubstrateException exception) {
            if (!"ALREADY_EXISTS".equals(exception.code())) throw exception;
        }
    }

    @Override
    public void createTemplate(TemplateSpec spec) {
        Container.Builder container = Container.newBuilder().setName("agent").setImage(spec.image())
                .addAllCommand(spec.command())
                .setWakeupProbe(ContainerWakeupProbe.newBuilder().setTimeoutSeconds(120)
                        .setHttpGet(HTTPGetAction.newBuilder().setPath(spec.wakeupPath()).setPort(spec.wakeupPort())))
                .addVolumeMounts(VolumeMount.newBuilder().setName("data").setMountPath("/data"));
        spec.env().forEach((name, value) -> container.addEnv(EnvVar.newBuilder().setName(name).setValue(value)));
        if(spec.projectIdentity()) container.addVolumeMounts(VolumeMount.newBuilder().setName("identity").setMountPath("/run/eap-identity"));
        ActorTemplate.Builder template = ActorTemplate.newBuilder()
                .setMetadata(ResourceMetadata.newBuilder().setAtespace(spec.atespace()).setName(spec.name()))
                .setWorkerSelector(Selector.newBuilder().putAllMatchLabels(spec.workerLabels()))
                .addContainers(container)
                .addVolumes(Volume.newBuilder().setName("data").setDurableDir(DurableDirVolumeSource.getDefaultInstance()))
                .setSnapshotsConfig(SnapshotsConfig.newBuilder()
                        .setOnPause(SnapshotContentScope.SNAPSHOT_CONTENT_SCOPE_FULL)
                        .setOnCommit(SnapshotContentScope.SNAPSHOT_CONTENT_SCOPE_FULL)
                        .setStorageLocation(spec.snapshotLocation()))
                .setSandboxConfig(com.eap.platform.substrate.ateapi.SandboxConfig.newBuilder()
                        .setSandboxClass(spec.microVm() ? SandboxClass.SANDBOX_CLASS_MICROVM : SandboxClass.SANDBOX_CLASS_GVISOR)
                        .setConfigName(spec.microVm() ? "microvm" : "gvisor-default"))
                .setResources(Resources.newBuilder()
                        .addLimits(Limits.newBuilder().setName("cpu").setQuantity(spec.cpu()))
                        .addLimits(Limits.newBuilder().setName("memory").setQuantity(spec.memory())));
        if(spec.projectIdentity()) template.addVolumes(Volume.newBuilder().setName("identity")
            .setSystemInfo(SystemInfoVolumeSource.newBuilder().addDataSources(SystemInfoDataSource.newBuilder()
                .setActorMetadata(ActorMetadataDataSource.newBuilder()
                    .addItems(ActorMetadataItem.newBuilder().setField(ActorMetadataField.ACTOR_METADATA_FIELD_ATESPACE).setPath("atespace"))
                    .addItems(ActorMetadataItem.newBuilder().setField(ActorMetadataField.ACTOR_METADATA_FIELD_NAME).setPath("name"))
                    .addItems(ActorMetadataItem.newBuilder().setField(ActorMetadataField.ACTOR_METADATA_FIELD_UID).setPath("uid"))))));
        rpc(stub -> stub.createActorTemplate(CreateActorTemplateRequest.newBuilder().setActorTemplate(template).build()));
    }

    @Override
    public GoldenStatus goldenStatus(String atespace, String template) {
        ActorTemplate current = rpc(stub -> stub.getActorTemplate(GetActorTemplateRequest.newBuilder()
                .setActorTemplate(ObjectRef.newBuilder().setAtespace(atespace).setName(template)).build()));
        var golden = current.getStatus().getGoldenSnapshotStatus();
        return new GoldenStatus(!golden.getGoldenTag().getName().isEmpty(),
                golden.getErrorMessage().isEmpty() ? null : golden.getErrorMessage());
    }

    @Override
    public ActorState createActor(SubstrateActorRef actor, String template) {
        return call(stub -> stub.createActor(CreateActorRequest.newBuilder().setActor(Actor.newBuilder()
                .setMetadata(ResourceMetadata.newBuilder().setAtespace(actor.atespace()).setName(actor.name()))
                .setActorTemplate(ObjectRef.newBuilder().setAtespace(actor.atespace()).setName(template))).build()));
    }

    @Override
    public ActorInventory createActorWithIdentity(SubstrateActorRef actor, String template) {
        return inventory(rpc(stub -> stub.createActor(CreateActorRequest.newBuilder().setActor(Actor.newBuilder()
            .setMetadata(ResourceMetadata.newBuilder().setAtespace(actor.atespace()).setName(actor.name()))
            .setActorTemplate(ObjectRef.newBuilder().setAtespace(actor.atespace()).setName(template))).build())));
    }

    @Override
    public void deleteActor(SubstrateActorRef actor) {
        try {
            rpc(stub -> stub.deleteActor(DeleteActorRequest.newBuilder().setActor(ref(actor)).setAnyState(true).build()));
        } catch (SubstrateException exception) {
            if (!"NOT_FOUND".equals(exception.code())) throw exception;
        }
    }

    @Override
    public void setEgressHostnames(SubstrateActorRef actor, List<String> hostnames) {
        if (hostnames.isEmpty()) {
            try {
                rpc(stub -> stub.deleteActorEgressPolicy(DeleteActorEgressPolicyRequest.newBuilder().setActor(ref(actor)).build()));
            } catch (SubstrateException exception) {
                if (!"NOT_FOUND".equals(exception.code())) throw exception;
            }
            return;
        }
        // Metadata is required; kubectl-ate names each actor's single policy "default" in the actor's atespace.
        EgressPolicy policy = EgressPolicy.newBuilder()
                .setMetadata(ResourceMetadata.newBuilder().setAtespace(actor.atespace()).setName("default"))
                .addRules(EgressRule.newBuilder().setHostnames(HostnameRule.newBuilder().addAllPatterns(hostnames))).build();
        try {
            rpc(stub -> stub.createActorEgressPolicy(CreateActorEgressPolicyRequest.newBuilder()
                    .setActor(ref(actor)).setEgressPolicy(policy).build()));
        } catch (SubstrateException exception) {
            if (!"ALREADY_EXISTS".equals(exception.code())) throw exception;
            EgressPolicy current = rpc(stub -> stub.getActorEgressPolicy(GetActorEgressPolicyRequest.newBuilder()
                    .setActor(ref(actor)).build()));
            var metadata = current.getMetadata();
            if (!actor.atespace().equals(metadata.getAtespace()) || !"default".equals(metadata.getName())
                    || metadata.getUid().isBlank() || metadata.getVersion() < 1) {
                throw new SubstrateException("FAILED_PRECONDITION", "出站策略缺少可核对的身份或版本，未覆盖策略");
            }
            if (current.getRulesList().equals(policy.getRulesList())) return;
            EgressPolicy replacement = policy.toBuilder().setMetadata(metadata).build();
            // Native UID/version are compare-and-set preconditions; conflicts are never retried blindly.
            rpc(stub -> stub.updateActorEgressPolicy(UpdateActorEgressPolicyRequest.newBuilder()
                    .setActor(ref(actor)).setEgressPolicy(replacement).build()));
        }
    }

    private ActorState call(Function<ControlGrpc.ControlBlockingStub, Actor> rpc) {
        Actor actor = rpc(rpc);
        return new ActorState(actor.getStatus().getState().name().replaceFirst("^ACTOR_STATE_", ""));
    }

    private <T> T rpc(Function<ControlGrpc.ControlBlockingStub, T> rpc) {
        if (!properties.isEnabled()) {
            throw new SubstrateException("UNAVAILABLE", "Substrate Runtime 集成未启用");
        }
        try {
            return rpc.apply(ControlGrpc.newBlockingStub(channel())
                    .withDeadlineAfter(properties.getCallTimeoutMs(), TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCause() != null) {
                log.warn("Substrate Control API call failed: {}", exception.getStatus(), exception.getStatus().getCause());
            }
            String description = exception.getStatus().getDescription();
            throw new SubstrateException(exception.getStatus().getCode().name(),
                    description == null ? exception.getStatus().getCode().name() : description);
        }
    }

    private static ObjectRef ref(SubstrateActorRef actor) {
        return ObjectRef.newBuilder().setAtespace(actor.atespace()).setName(actor.name()).build();
    }

    private synchronized ManagedChannel channel() {
        if (channel == null) {
            try {
                // Substrate issues Ed25519 service certificates. The shaded BoringSSL engine offers the JDK
                // trust manager no Ed25519 certificate signature scheme, so validation fails; the JDK TLS stack accepts it.
                channel = NettyChannelBuilder.forTarget(properties.getApiTarget())
                        .overrideAuthority(properties.getApiAuthority())
                        .sslContext(GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK)
                                .trustManager(new File(properties.getCaFile())).build())
                        .intercept(new BearerTokenInterceptor(Path.of(properties.getTokenFile())))
                        .build();
            } catch (IOException | IllegalArgumentException exception) {
                throw new SubstrateException("UNAVAILABLE", "无法读取 Substrate 服务端信任证书：" + properties.getCaFile());
            }
        }
        return channel;
    }

    @PreDestroy
    public synchronized void close() {
        if (channel != null) channel.shutdownNow();
    }

    private record BearerTokenInterceptor(Path tokenFile) implements ClientInterceptor {
        @Override
        public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                @Override
                public void start(Listener<R> listener, Metadata headers) {
                    try {
                        headers.put(AUTHORIZATION, "Bearer " + Files.readString(tokenFile, StandardCharsets.UTF_8).trim());
                    } catch (IOException exception) {
                        throw new SubstrateException("UNAUTHENTICATED", "无法读取 Substrate 访问令牌：" + tokenFile);
                    }
                    super.start(listener, headers);
                }
            };
        }
    }
}
