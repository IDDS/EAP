package com.eap.platform.observability;

import com.eap.platform.config.MachineTokenValidator;
import com.eap.platform.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentObservationControllerTests {
    @Test
    void developmentModeNeverReturnsCapturedContent() {
        ContentObservationService service = mock(ContentObservationService.class);
        MachineTokenValidator validator = mock(MachineTokenValidator.class);
        SecurityProperties security = new SecurityProperties();
        ContentObservationDtos.ContentObservationResponse observation = observation("sanitized business content");
        when(service.list(100, 4L, null, null, null, null, null, 0)).thenReturn(List.of(observation));

        ContentObservationController controller = new ContentObservationController(service, validator, security);
        var response = controller.list(100, 4L, null, null, null, null, null, 0);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().contentJson()).isNull();
        assertThat(response.data().getFirst().contentHash()).isEqualTo("hash");
    }

    @Test
    void jwtModeKeepsSanitizedContentForAuthorizedRequestPath() {
        ContentObservationService service = mock(ContentObservationService.class);
        MachineTokenValidator validator = mock(MachineTokenValidator.class);
        SecurityProperties security = new SecurityProperties();
        security.setMode(SecurityProperties.Mode.JWT);
        ContentObservationDtos.ContentObservationResponse observation = observation("sanitized business content");
        when(service.list(100, 4L, null, null, null, null, null, 0)).thenReturn(List.of(observation));

        ContentObservationController controller = new ContentObservationController(service, validator, security);
        var response = controller.list(100, 4L, null, null, null, null, null, 0);

        assertThat(response.data().getFirst().contentJson()).isEqualTo("sanitized business content");
    }

    private ContentObservationDtos.ContentObservationResponse observation(String content) {
        Instant now = Instant.parse("2026-08-14T00:00:00Z");
        return new ContentObservationDtos.ContentObservationResponse(
                1L, "obs-1", "event-1", "trace-1", "span-1", 4L, "endpoint-1", null,
                "deepseek", "deepseek-v4-pro", ContentDirection.INPUT, ContentCaptureMode.REDACTED,
                "hash", 128L, 32L, content, "MODEL_INPUT", "INTERNAL", "REDACT", true, now, now
        );
    }
}
