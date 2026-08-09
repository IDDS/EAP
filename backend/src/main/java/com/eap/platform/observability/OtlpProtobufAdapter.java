package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.springframework.stereotype.Component;

@Component
public class OtlpProtobufAdapter {
    private final ObjectMapper objectMapper;

    public OtlpProtobufAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode toJson(byte[] payload) {
        try {
            ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(payload);
            return objectMapper.readTree(JsonFormat.printer().print(request));
        } catch (InvalidProtocolBufferException exception) {
            throw new BusinessException("OTLP Protobuf 请求格式不合法");
        } catch (Exception exception) {
            throw new BusinessException("OTLP Protobuf 请求无法转换");
        }
    }
}
