package com.eap.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.InstrumentationLibrarySpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpProtobufAdapterTests {
    @Test
    void convertsOfficialExportTraceRequestIntoOtlpJsonShape() {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(KeyValue.newBuilder()
                                .setKey("service.name")
                                .setValue(AnyValue.newBuilder().setStringValue("protobuf-agent"))))
                        .addInstrumentationLibrarySpans(InstrumentationLibrarySpans.newBuilder().addSpans(Span.newBuilder()
                                .setTraceId(ByteString.copyFrom(java.util.HexFormat.of().parseHex("00112233445566778899aabbccddeeff")))
                                .setSpanId(ByteString.copyFrom(java.util.HexFormat.of().parseHex("0011223344556677")))
                                .setName("chat model")
                                .addAttributes(KeyValue.newBuilder().setKey("gen_ai.request.model")
                                        .setValue(AnyValue.newBuilder().setStringValue("demo-model"))))))
                .build();

        var json = new OtlpProtobufAdapter(new ObjectMapper()).toJson(request.toByteArray());

        assertThat(json.path("resourceSpans").isArray()).isTrue();
        assertThat(json.path("resourceSpans").get(0).path("instrumentationLibrarySpans").get(0)
                .path("spans").get(0).path("name").asText()).isEqualTo("chat model");
    }
}
