package com.eap.platform.observability;

import java.util.List;

public final class OtlpTraceIngestionDtos {
    private OtlpTraceIngestionDtos() {
    }

    public record IngestionResponse(
            int spansScanned,
            int genAiSpansDetected,
            int invocationsRecorded,
            int contentObservationsRecorded,
            int endpointsRegistered,
            List<String> warnings
    ) {
    }
}
