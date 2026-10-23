package com.eap.platform.management;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ManagementConnectorCredentialPolicyTests {
    private ManagementConnectorProperties properties() {
        var p = new ManagementConnectorProperties();
        var binding = new ManagementConnectorProperties.CredentialBinding();
        binding.setEnvKey("SYNTHETIC_CRM_CREDENTIAL");
        binding.setOrigin("https://crm.example.invalid"); binding.setConnectorKeys(Set.of("crm-deals"));
        p.setCredentials(Map.of("crm-readonly", binding)); return p;
    }
    private ManagementValueConnector connector() {
        var c = new ManagementValueConnector(); c.connectorKey = "crm-deals";
        c.endpointUrl = "https://crm.example.invalid/api/deals";
        c.authType = "BEARER_ENV"; c.credentialEnvKey = "crm-readonly"; return c;
    }
    @Test void resolvesOnlyRegisteredSecretAfterCheckingActualTarget() {
        var p = properties(); var c = connector(); var reads = new AtomicInteger();
        var policy = new ManagementConnectorCredentialPolicy(p, key -> {
            assertThat(key).isEqualTo("SYNTHETIC_CRM_CREDENTIAL"); reads.incrementAndGet(); return "synthetic-sentinel";
        });
        policy.validate(c); assertThat(reads).hasValue(0);
        var request = HttpRequest.newBuilder(URI.create(c.endpointUrl)); policy.authorize(request, c);
        assertThat(request.build().headers().firstValue("Authorization")).contains("Bearer synthetic-sentinel");
        assertThat(reads).hasValue(1);
        assertThatThrownBy(() -> policy.authorize(HttpRequest.newBuilder(URI.create("https://elsewhere.invalid")), c)).isInstanceOf(BusinessException.class);
        assertThat(reads).hasValue(1);
    }
    @ParameterizedTest
    @ValueSource(strings = {"unregistered", "variable", "connector", "host", "port", "scheme", "userinfo", "header", "prefix", "type"})
    void refusesChangedBindingsBeforeReadingAnyEnvironmentValue(String mutation) {
        var c = connector();
        switch (mutation) {
            case "unregistered" -> c.credentialEnvKey = "unknown";
            case "variable" -> c.credentialEnvKey = "SYNTHETIC_CRM_CREDENTIAL";
            case "connector" -> c.connectorKey = "another-connector";
            case "host" -> c.endpointUrl = "https://other.invalid";
            case "port" -> c.endpointUrl = "https://crm.example.invalid:444/";
            case "scheme" -> c.endpointUrl = "http://crm.example.invalid/";
            case "userinfo" -> c.endpointUrl = "https://user@crm.example.invalid/";
            case "header" -> c.authHeader = "X-Wrong";
            case "prefix" -> c.authPrefix = "Wrong ";
            case "type" -> c.authType = "HEADER_ENV";
        }
        var policy = new ManagementConnectorCredentialPolicy(properties(), key -> { throw new AssertionError("secret read before authorization"); });
        assertThatThrownBy(() -> policy.authorize(HttpRequest.newBuilder(URI.create(c.endpointUrl)), c)).isInstanceOf(BusinessException.class);
    }
    @Test void unregisteredLegacyConfigurationFailsClosedAndNoneDoesNotReadSecrets() {
        var policy = new ManagementConnectorCredentialPolicy(new ManagementConnectorProperties(), key -> { throw new AssertionError("secret read"); });
        var c = connector(); assertThatThrownBy(() -> policy.validate(c)).isInstanceOf(BusinessException.class);
        c.authType = "NONE"; var request = HttpRequest.newBuilder(URI.create(c.endpointUrl));
        policy.authorize(request, c); assertThat(request.build().headers().map()).isEmpty();
    }
    @Test void supportsPinnedHeaderAndCanonicalHttpsPort() {
        var p = properties(); var b = p.getCredentials().get("crm-readonly");
        b.setAuthType("HEADER_ENV"); b.setHeader("X-CRM-Key"); b.setPrefix("");
        var c = connector(); c.authType = "HEADER_ENV"; c.endpointUrl = "https://CRM.example.invalid:443/deals?since=1";
        var request = HttpRequest.newBuilder(URI.create(c.endpointUrl));
        new ManagementConnectorCredentialPolicy(p, key -> "synthetic").authorize(request, c);
        assertThat(request.build().headers().firstValue("X-CRM-Key")).contains("synthetic");
    }
    @Test void secretErrorsNeverEchoValueOrVariableName() {
        var c = connector(); var policy = new ManagementConnectorCredentialPolicy(properties(), key -> "synthetic\ninvalid");
        assertThatThrownBy(() -> policy.authorize(HttpRequest.newBuilder(URI.create(c.endpointUrl)), c))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("synthetic").hasMessageNotContaining("SYNTHETIC_CRM_CREDENTIAL");
    }
}
