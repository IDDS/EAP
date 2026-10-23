package com.eap.platform.observability;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentObservationServiceTests {
    @Test
    void redactedModeStoresSafeContentButHashesOriginal() {
        ContentObservationRepository repository = mock(ContentObservationRepository.class);
        when(repository.findByObservationKey("obs-1")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObservationProperties properties = new ObservationProperties();
        properties.setCaptureMode(ContentCaptureMode.REDACTED);
        ContentObservationService service = new ContentObservationService(repository, properties);

        ContentObservationDtos.ContentObservationResponse response = service.capture(
                new ContentObservationDtos.CaptureRequest(
                        "obs-1", "event-1", "trace-1", "span-1", 1L, null, null,
                        "openai", "demo", ContentDirection.INPUT,
                        "password=super-secret", "password=[REDACTED]", "MODEL_INPUT", "SECRET", "REDACT", null
                )
        );

        assertThat(response.captureMode()).isEqualTo(ContentCaptureMode.REDACTED);
        assertThat(response.contentJson()).isEqualTo("password=[REDACTED]");
        assertThat(response.contentHash()).doesNotContain("super-secret");
        assertThat(response.redactionApplied()).isTrue();
    }

    @Test
    void hashOnlyModeDoesNotPersistContent() {
        ContentObservationRepository repository = mock(ContentObservationRepository.class);
        when(repository.findByObservationKey("obs-2")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ContentObservationService service = new ContentObservationService(repository, new ObservationProperties());

        ContentObservationDtos.ContentObservationResponse response = service.capture(
                new ContentObservationDtos.CaptureRequest(
                        "obs-2", "event-2", "trace-2", null, null, "endpoint-1", null,
                        "local", "model", ContentDirection.OUTPUT, "private output", "safe output",
                        "MODEL_OUTPUT", "INTERNAL", "ALLOW", null
                )
        );

        assertThat(response.captureMode()).isEqualTo(ContentCaptureMode.HASH_ONLY);
        assertThat(response.contentJson()).isNull();
        assertThat(response.contentBytes()).isGreaterThan(0);
    }
}
