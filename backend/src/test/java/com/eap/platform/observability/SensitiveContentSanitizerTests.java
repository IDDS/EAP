package com.eap.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SensitiveContentSanitizerTests {
    private final SensitiveContentSanitizer sanitizer = new SensitiveContentSanitizer();
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"password\":\"synthetic-marker\"}",
        "{\"api_key\":\"synthetic-marker\"}",
        "{\"records\":[{\"Authorization\":\"synthetic-marker\"}]}",
        "{\"nested\":\"{\\\"access_token\\\":\\\"synthetic-marker\\\"}\"}",
        "{\"password\":\"synthetic-marker",
        "password=synthetic-marker",
        "request: {\"password\":\"synthetic-marker\"}",
        "password=\"synthetic-marker with spaces\""
    })
    void removesStructuredAndTextCredentials(String input) {
        var result = sanitizer.sanitize(input);
        assertThat(result.safeContent()).doesNotContain("synthetic-marker");
        assertThat(result.redactionApplied()).isTrue(); assertThat(result.sensitivity()).isNotEqualTo("PUBLIC");
    }
    @Test void preservesSafeJsonShapeAndFormatting() throws Exception {
        String input = "{ \"facts\": [1,true,null], \"answer\": \"normal\" }";
        assertThat(sanitizer.sanitize(input).safeContent()).isEqualTo(input);
        var safe = sanitizer.sanitize("{\"password\":\"synthetic-marker\",\"facts\":[1,true]}");
        assertThat(new ObjectMapper().readTree(safe.safeContent()).path("facts").size()).isEqualTo(2);
    }
    @Test void stillWithholdsInternalTextAndBoundsNestedValues() {
        assertThat(sanitizer.sanitize("内部资料").safeContent()).isNull();
        String input = "[".repeat(40) + "\"synthetic-marker\"" + "]".repeat(40);
        assertThat(sanitizer.sanitize(input).safeContent()).doesNotContain("synthetic-marker");
    }
}
