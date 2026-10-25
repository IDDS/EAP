package com.eap.platform.management;

import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** A connector can select a registered reference, never an arbitrary process variable. */
@Component
public class ManagementConnectorCredentialPolicy {
    private final ManagementConnectorProperties properties;
    private final Function<String, String> secrets;
    @org.springframework.beans.factory.annotation.Autowired
    public ManagementConnectorCredentialPolicy(ManagementConnectorProperties properties) {
        this(properties, System::getenv);
    }
    ManagementConnectorCredentialPolicy(ManagementConnectorProperties properties, Function<String, String> secrets) {
        this.properties = properties; this.secrets = secrets;
    }
    public void validate(ManagementValueConnector connector) { binding(connector); }
    public void authorize(HttpRequest.Builder request, ManagementValueConnector connector) {
        var binding = binding(connector);
        if (binding == null) return;
        // Re-check the actual request destination, not only the saved configuration.
        if (!origin(request.build().uri()).equals(origin(URI.create(binding.getOrigin())))) throw denied();
        String secret = secrets.apply(binding.getEnvKey());
        if (secret == null || secret.isBlank() || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)
            throw new BusinessException("连接器凭据暂不可用，请联系平台管理员");
        try { request.header(binding.getHeader(), binding.getPrefix() + secret); }
        catch (IllegalArgumentException exception) { throw new BusinessException("连接器凭据暂不可用，请联系平台管理员"); }
    }
    private ManagementConnectorProperties.CredentialBinding binding(ManagementValueConnector connector) {
        String type = connector.authType == null ? "NONE" : connector.authType;
        if ("NONE".equals(type)) return null;
        var b = connector.credentialEnvKey == null ? null : properties.getCredentials().get(connector.credentialEnvKey);
        if (b == null || !Set.of("BEARER_ENV", "HEADER_ENV").contains(type)
                || !type.equals(b.getAuthType()) || !b.getConnectorKeys().contains(connector.connectorKey)) throw denied();
        try {
            URI registered = URI.create(b.getOrigin());
            URI target = URI.create(connector.endpointUrl);
            if (registered.getRawQuery() != null || registered.getRawFragment() != null
                    || !(registered.getRawPath().isEmpty() || "/".equals(registered.getRawPath()))
                    || !origin(registered).equals(origin(target))) throw denied();
            if (b.getEnvKey() == null || !b.getEnvKey().matches("[A-Za-z_][A-Za-z0-9_]*")
                    || b.getHeader() == null || !b.getHeader().matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                    || Set.of("host", "content-length", "connection", "transfer-encoding", "cookie").contains(b.getHeader().toLowerCase(Locale.ROOT))
                    || b.getPrefix() == null || b.getPrefix().chars().anyMatch(c -> c < 32 || c > 126)
                    || "BEARER_ENV".equals(type) && (!"Authorization".equalsIgnoreCase(b.getHeader()) || !"Bearer ".equals(b.getPrefix()))
                    || connector.authHeader != null && !connector.authHeader.isBlank() && !connector.authHeader.equalsIgnoreCase(b.getHeader())
                    || connector.authPrefix != null && !connector.authPrefix.isBlank() && !connector.authPrefix.equals(b.getPrefix())) throw denied();
            return b;
        } catch (IllegalArgumentException | NullPointerException exception) { throw denied(); }
    }
    private String origin(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw denied();
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (port < 1 || port > 65535) throw denied();
        return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
    private BusinessException denied() { return new BusinessException("凭据引用未获准用于此连接器、目标或认证方式，请联系平台管理员"); }
}
