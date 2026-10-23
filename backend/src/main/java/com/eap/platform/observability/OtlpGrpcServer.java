package com.eap.platform.observability;

import com.eap.platform.config.MachineTokenValidator;
import com.eap.platform.config.UnauthorizedException;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "eap.observability.otlp.grpc.enabled", havingValue = "true", matchIfMissing = true)
public class OtlpGrpcServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);
    private static final Metadata.Key<String> TOKEN = Metadata.Key.of(
            "x-eap-observability-token", Metadata.ASCII_STRING_MARSHALLER);

    private final OtlpGrpcProperties properties;
    private final OtlpTraceIngestionService service;
    private final OtlpProtobufAdapter protobufAdapter;
    private final ContentObservationService contentObservationService;
    private final MachineTokenValidator machineTokenValidator;
    private volatile Server server;
    private volatile boolean running;

    public OtlpGrpcServer(
            OtlpGrpcProperties properties,
            OtlpTraceIngestionService service,
            OtlpProtobufAdapter protobufAdapter,
            ContentObservationService contentObservationService,
            MachineTokenValidator machineTokenValidator
    ) {
        this.properties = properties;
        this.service = service;
        this.protobufAdapter = protobufAdapter;
        this.contentObservationService = contentObservationService;
        this.machineTokenValidator = machineTokenValidator;
    }

    @Override
    public void start() {
        if (running) return;
        try {
            server = NettyServerBuilder.forPort(properties.getPort())
                    .maxInboundMessageSize(Math.max(65_536, properties.getMaxInboundMessageBytes()))
                    .addService(new TraceService().bindService())
                    .intercept(new TokenInterceptor())
                    .build()
                    .start();
            running = true;
            log.info("OTLP gRPC TraceService listening on port {}", properties.getPort());
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 OTLP gRPC 端口 " + properties.getPort(), exception);
        }
    }

    @Override
    public void stop() {
        Server current = server;
        running = false;
        if (current == null) return;
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) current.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() { return running; }

    int boundPort() { return server == null ? -1 : server.getPort(); }

    @Override
    public boolean isAutoStartup() { return properties.isEnabled(); }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; }

    private final class TraceService extends TraceServiceGrpc.TraceServiceImplBase {
        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> observer) {
            try {
                service.ingest(protobufAdapter.toJson(request.toByteArray()));
                observer.onNext(ExportTraceServiceResponse.getDefaultInstance());
                observer.onCompleted();
            } catch (RuntimeException exception) {
                observer.onError(Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asRuntimeException());
            }
        }
    }

    private final class TokenInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call,
                Metadata headers,
                ServerCallHandler<ReqT, RespT> next
        ) {
            try {
                machineTokenValidator.require(
                        contentObservationService.ingestionToken(), headers.get(TOKEN), "OTLP gRPC 深度观测上报凭证");
                return Contexts.interceptCall(Context.current(), call, headers, next);
            } catch (UnauthorizedException exception) {
                call.close(Status.UNAUTHENTICATED.withDescription(exception.getMessage()), new Metadata());
                return new ServerCall.Listener<>() { };
            }
        }
    }
}
