package com.eap.platform.deployment;

import com.eap.platform.substrate.SubstrateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs kaniko as a Kubernetes Job through the Kubernetes API, authenticated with the backend pod's own
 * service account (which needs create/get/delete on jobs and get/list on pods and pods/log in the build
 * namespace, nothing else). kaniko writes the pushed digest to the termination message, so the image is
 * pinned without trusting a tag.
 *
 * Builds execute the repository's Dockerfile, i.e. arbitrary code: they run in a dedicated namespace
 * without a service account token, with resource limits and a deadline.
 */
@Component
public class KanikoImageBuilder implements ImageBuilder {
    private static final Path SA = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    private final SubstrateProperties properties;
    private final ObjectMapper mapper;
    private final com.eap.platform.config.ProjectIsolation project;
    private HttpClient http;

    public KanikoImageBuilder(SubstrateProperties properties, ObjectMapper mapper, com.eap.platform.config.ProjectIsolation project) {
        this.project = project;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public boolean enabled() {
        return properties.isBuildEnabled() && System.getenv("KUBERNETES_SERVICE_HOST") != null;
    }

    @Override
    public String repository(Long agentId) {
        return properties.getLocalRegistryPrefix() + repositoryName(agentId);
    }

    private String repositoryName(Long agentId) {
        String key = project.context().projectKey();
        return (key == null ? "eap" : "eap-" + key) + "-src-a" + agentId;
    }

    @Override
    public String destination(Long agentId, Integer version) {
        return properties.getBuildPushPrefix() + repositoryName(agentId) + ":v" + version;
    }

    @Override
    public void start(BuildSpec spec) {
        project.requireBuildNamespace(properties.getBuildNamespace());
        List<String> args = new ArrayList<>(List.of(
                "--context=" + gitContext(spec.gitUrl(), spec.gitRef()),
                "--dockerfile=" + spec.dockerfile(),
                "--destination=" + spec.destination(),
                "--digest-file=/dev/termination-log",
                "--snapshot-mode=redo",
                "--use-new-run",
                "--insecure",
                "--skip-tls-verify-registry=" + host(spec.destination())));
        if (!spec.contextPath().isBlank()) args.add("--context-sub-path=" + spec.contextPath());
        if (!properties.getBuildRegistryMirror().isBlank()) args.add("--registry-mirror=" + properties.getBuildRegistryMirror());
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "kaniko");
        container.put("image", properties.getBuildImage());
        container.put("args", args);
        container.put("resources", Map.of("requests", Map.of("cpu", "200m", "memory", "512Mi"),
                "limits", Map.of("cpu", "2", "memory", "2Gi")));
        container.put("terminationMessagePolicy", "File");
        // kaniko clones git:// contexts over https unless GIT_PULL_METHOD says otherwise.
        if (spec.gitUrl().startsWith("http://")) container.put("env", List.of(Map.of("name", "GIT_PULL_METHOD", "value", "http")));
        Map<String, Object> job = Map.of(
                "apiVersion", "batch/v1", "kind", "Job",
                "metadata", Map.of("name", spec.name(), "labels", Map.of("app.kubernetes.io/managed-by", "eap")),
                "spec", Map.of(
                        "backoffLimit", 0,
                        "activeDeadlineSeconds", properties.getBuildTimeoutSeconds(),
                        "ttlSecondsAfterFinished", 86400,
                        "template", Map.of(
                                "metadata", Map.of("labels", Map.of("app.kubernetes.io/managed-by", "eap")),
                                "spec", Map.of(
                                        "restartPolicy", "Never",
                                        "automountServiceAccountToken", false,
                                        "enableServiceLinks", false,
                                        "containers", List.of(container)))));
        HttpResponse<String> response = send("POST", jobsPath(), json(job));
        if (response.statusCode() != 201 && response.statusCode() != 409) {
            throw new BuildException("创建构建任务失败（HTTP " + response.statusCode() + "）：" + message(response.body()));
        }
    }

    @Override
    public BuildStatus status(String name) {
        HttpResponse<String> response = send("GET", jobsPath() + "/" + name, null);
        if (response.statusCode() == 404) return new BuildStatus("MISSING", null, "构建任务不存在");
        if (response.statusCode() != 200) throw new BuildException("读取构建任务失败（HTTP " + response.statusCode() + "）");
        JsonNode status = read(response.body()).path("status");
        for (JsonNode condition : status.path("conditions")) {
            if (!"True".equals(condition.path("status").asText())) continue;
            String type = condition.path("type").asText();
            if ("Complete".equals(type)) {
                String digest = terminationMessage(name).trim();
                return digest.matches("sha256:[a-f0-9]{64}")
                        ? new BuildStatus("SUCCEEDED", digest, null)
                        : new BuildStatus("FAILED", null, "构建完成但未取得镜像摘要");
            }
            if ("Failed".equals(type)) {
                return new BuildStatus("FAILED", null, "DeadlineExceeded".equals(condition.path("reason").asText())
                        ? "构建超时（" + properties.getBuildTimeoutSeconds() + " 秒）" : "构建失败，请查看构建日志");
            }
        }
        return new BuildStatus("RUNNING", null, null);
    }

    @Override
    public String logTail(String name, int lines) {
        String pod = podName(name);
        if (pod == null) return "";
        HttpResponse<String> response = send("GET", podsPath() + "/" + pod + "/log?tailLines=" + Math.max(1, Math.min(lines, 500)), null);
        return response.statusCode() == 200 ? response.body() : "";
    }

    @Override
    public void delete(String name) {
        HttpResponse<String> response = send("DELETE", jobsPath() + "/" + name + "?propagationPolicy=Background", null);
        if (response.statusCode() != 200 && response.statusCode() != 202 && response.statusCode() != 404) {
            throw new BuildException("删除构建任务失败（HTTP " + response.statusCode() + "）");
        }
    }

    /** kaniko takes git://host/path (scheme via GIT_PULL_METHOD); a commit is passed as-is, a branch as refs/heads/. */
    static String gitContext(String gitUrl, String gitRef) {
        String ref = gitRef.matches("[a-f0-9]{40}") || gitRef.startsWith("refs/") ? gitRef : "refs/heads/" + gitRef;
        return "git://" + gitUrl.replaceFirst("^https?://", "") + "#" + ref;
    }

    private String terminationMessage(String name) {
        String pod = podName(name);
        if (pod == null) return "";
        JsonNode statuses = read(send("GET", podsPath() + "/" + pod, null).body()).path("status").path("containerStatuses");
        return statuses.isArray() && !statuses.isEmpty()
                ? statuses.get(0).path("state").path("terminated").path("message").asText("") : "";
    }

    private String podName(String job) {
        HttpResponse<String> response = send("GET", podsPath() + "?labelSelector="
                + URLEncoder.encode("job-name=" + job, StandardCharsets.UTF_8), null);
        if (response.statusCode() != 200) return null;
        JsonNode items = read(response.body()).path("items");
        return items.isArray() && !items.isEmpty() ? items.get(items.size() - 1).path("metadata").path("name").asText() : null;
    }

    private String jobsPath() {
        project.requireBuildNamespace(properties.getBuildNamespace());
        return "/apis/batch/v1/namespaces/" + properties.getBuildNamespace() + "/jobs";
    }

    private String podsPath() {
        project.requireBuildNamespace(properties.getBuildNamespace());
        return "/api/v1/namespaces/" + properties.getBuildNamespace() + "/pods";
    }

    private HttpResponse<String> send(String method, String path, String body) {
        if (!enabled()) throw new BuildException("源码构建未启用");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://" + System.getenv("KUBERNETES_SERVICE_HOST") + ":"
                            + System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443") + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + Files.readString(SA.resolve("token")).trim())
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (BuildException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BuildException("Kubernetes API 请求被中断");
        } catch (Exception exception) {
            throw new BuildException("无法访问 Kubernetes API：" + exception.getClass().getSimpleName());
        }
    }

    private synchronized HttpClient client() throws Exception {
        if (http == null) {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            try (InputStream ca = Files.newInputStream(SA.resolve("ca.crt"))) {
                int index = 0;
                for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(ca)) {
                    trust.setCertificateEntry("k8s-ca-" + index++, certificate);
                }
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            http = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        }
        return http;
    }

    private static String host(String reference) {
        return reference.substring(0, reference.indexOf('/'));
    }

    private String message(String body) {
        return read(body).path("message").asText("").replaceAll("\\s+", " ");
    }

    private JsonNode read(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception exception) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BuildException("构建任务无法序列化");
        }
    }
}
