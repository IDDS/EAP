package com.eap.platform.observability;

import com.eap.platform.config.MachineTokenValidator;
import com.eap.platform.config.SecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtlpGrpcServerTests {
    @Test
    void acceptsOfficialTraceServiceExportOnGrpcPort() throws Exception {
        OtlpGrpcProperties properties = new OtlpGrpcProperties();
        properties.setPort(0);
        OtlpTraceIngestionService ingestionService = mock(OtlpTraceIngestionService.class);
        ContentObservationService contentService = mock(ContentObservationService.class);
        when(contentService.ingestionToken()).thenReturn("");
        OtlpGrpcServer server = new OtlpGrpcServer(
                properties,
                ingestionService,
                new OtlpProtobufAdapter(new ObjectMapper()),
                contentService,
                new MachineTokenValidator(new SecurityProperties())
        );
        ManagedChannel channel = null;
        try {
            server.start();
            assertThat(server.boundPort()).isPositive();
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.boundPort()).usePlaintext().build();

            TraceServiceGrpc.newBlockingStub(channel).export(ExportTraceServiceRequest.getDefaultInstance());

            verify(ingestionService).ingest(any());
        } finally {
            if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.stop();
        }
    }
}
