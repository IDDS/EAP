package com.eap.platform.deployment;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.registry.AgentRegistryDtos;
import com.eap.platform.registry.AgentRegistryService;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import com.eap.platform.safety.GenseeSafetyService;
import com.eap.platform.safety.SafetyDecision;
import com.eap.platform.safety.SafetyDtos;
import com.eap.platform.safety.SafetyEventType;
import com.eap.platform.substrate.SubstrateActorRef;
import com.eap.platform.substrate.SubstrateClient;
import com.eap.platform.substrate.SubstrateProperties;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Console deployment of agents onto Agent Substrate: validate and preflight a request, then advance it
 * step by step (template, golden snapshot, first instance). External calls run outside database
 * transactions; every step is persisted before the next one starts, so a restart resumes where it stopped.
 */
@Service
public class SubstrateDeploymentService {
    static final String BUILDING_IMAGE = "BUILDING_IMAGE";
    static final String QUEUED = "QUEUED";
    static final String BUILDING = "BUILDING_GOLDEN";
    static final String CREATING = "CREATING_INSTANCE";
    static final String CONNECTING = "CONNECTING_IDENTITY";
    static final String READY = "READY";
    static final String FAILED = "FAILED";
    static final String BLOCKED = "BLOCKED";
    static final String WAITING = "WAITING_APPROVAL";

    private static final Map<String, String[]> SIZES = new LinkedHashMap<>();
    static {
        SIZES.put("SMALL", new String[]{"500m", "256Mi"});
        SIZES.put("MEDIUM", new String[]{"1", "512Mi"});
        SIZES.put("LARGE", new String[]{"2", "1Gi"});
    }
    private static final Pattern HOST = Pattern.compile("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]{0,63}$");
    private static final Pattern SECRET_NAME = Pattern.compile(".*(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL).*");
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern PROBE_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,255}$");
    private static final Pattern DIGEST_REF = Pattern.compile("^[a-z0-9.:/_-]+@sha256:[a-f0-9]{64}$");
    private static final Pattern TAG_REF = Pattern.compile("^([a-z0-9._/-]+?)(?::([A-Za-z0-9._-]{1,128}))?$");
    private static final Pattern REQUEST_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern GIT_URL = Pattern.compile("^(https?)://([A-Za-z0-9.-]+)(:\\d{1,5})?/[A-Za-z0-9._~/-]+$");
    private static final Pattern GIT_REF = Pattern.compile("^(?!.*\\.\\.)[A-Za-z0-9._/-]{1,200}$");
    private static final Pattern REPO_PATH = Pattern.compile("^(?!/)(?!.*\\.\\.)[A-Za-z0-9._/-]{0,200}$");
    private static final Pattern INSTANCE_NAME = Pattern.compile("^a(\\d+)-v(\\d+)-[a-z0-9]{6,12}$");

    private final SubstrateDeploymentRepository repository;
    private final AgentRepository agents;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentRegistryService registry;
    private final SubstrateClient client;
    private final SubstrateProperties properties;
    private final WorkflowExecutionProperties workflow;
    private final com.eap.platform.observability.ObservationProperties observation;
    private final GenseeSafetyService safety;
    private final AuditLogRepository audits;
    private final ImageBuilder builder;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final com.eap.platform.registry.RuntimeWorkService work;
    private final PlatformRuntimeCreationService owned;
    private final com.eap.platform.registry.RuntimeConnectionProperties identityConfig;
    private final com.eap.platform.registry.RuntimeConnectionService connections;
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.observability.ObservationCoverageService coverage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public SubstrateDeploymentService(SubstrateDeploymentRepository repository, AgentRepository agents,
                                      AgentRuntimeInstanceRepository runtimes, AgentRegistryService registry,
                                      SubstrateClient client, SubstrateProperties properties,
                                      WorkflowExecutionProperties workflow, com.eap.platform.observability.ObservationProperties observation, GenseeSafetyService safety,
                                      AuditLogRepository audits, ImageBuilder builder, ObjectMapper mapper,
                                      PlatformTransactionManager manager, PlatformRuntimeCreationService owned,
                                      com.eap.platform.registry.RuntimeConnectionProperties identityConfig,
                                      com.eap.platform.registry.RuntimeConnectionService connections, com.eap.platform.registry.RuntimeWorkService work) {
        this.repository = repository; this.agents = agents; this.runtimes = runtimes; this.registry = registry;
        this.client = client; this.properties = properties; this.workflow = workflow; this.observation = observation; this.safety = safety;
        this.owned=owned;this.identityConfig=identityConfig;this.connections=connections;this.work=work;
        this.audits = audits; this.builder = builder; this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SubstrateDeploymentDtos.CapabilityResponse capabilities() {
        return new SubstrateDeploymentDtos.CapabilityResponse(properties.isEnabled(), !properties.getRunnerImage().isBlank() && identityConfig.deliveryReady(),
                !properties.getGatewayUrl().isBlank() && !properties.getGatewayToken().isBlank(),
                properties.getDeployAtespace(), properties.getGatewayHost(), List.copyOf(SIZES.keySet()), builder.enabled(), identityConfig.deliveryReady(),
                properties.kagentRunnerAvailable() && identityConfig.deliveryReady(),
                FrameworkSamples.ALL.stream().map(sample -> new SubstrateDeploymentDtos.FrameworkSampleCapability(
                        sample.key(), sample.label(), sample.frameworkVersion(), frameworkSampleUnavailable() == null,
                        frameworkSampleUnavailable())).toList());
    }

    private String frameworkSampleUnavailable() {
        if (!properties.isEnabled()) return "托管运行环境未启用";
        if (!identityConfig.deliveryReady()) return "专属实例身份配送未就绪";
        if (!workflow.isEnabled() || workflow.hosts().isEmpty()) return "任务派发未就绪";
        if (properties.getGatewayUrl().isBlank() || properties.getGatewayHost().isBlank()) return "治理网关未配置";
        if (!properties.frameworkSampleAvailable()) return "管理员尚未配置按摘要固定的样例镜像";
        return null;
    }

    @Transactional(readOnly = true)
    public List<SubstrateDeploymentDtos.DeploymentResponse> list(Long agentId) {
        return repository.findTop20ByAgentIdOrderByVersionDesc(agentId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SubstrateDeploymentDtos.DeploymentContent content(Long agentId, Integer version) {
        SubstrateDeployment deployment = repository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException("部署版本不存在"));
        Object prompt = config(deployment).get("systemPrompt");
        return new SubstrateDeploymentDtos.DeploymentContent(deployment.deploymentKey, deployment.agentId,
                deployment.version, prompt instanceof String value ? value : null);
    }

    @Transactional(readOnly = true)
    public SubstrateDeploymentDtos.DeploymentReceipt receipt(Long agentId, String requestKey) {
        if (requestKey == null || !REQUEST_KEY.matcher(requestKey).matches())
            throw new BusinessException("requestKey 必须是 1–64 位字母、数字、下划线或短横线");
        // Query the durable key directly, including versions outside the latest-20 list.
        // Absence is only a snapshot: an earlier POST can still be committing.
        return repository.findByAgentIdAndSubmissionKey(agentId, requestKey)
                .map(d -> new SubstrateDeploymentDtos.DeploymentReceipt(requestKey, agentId, d.deploymentKey,
                        d.version, d.status, d.instanceKey))
                .orElseGet(() -> new SubstrateDeploymentDtos.DeploymentReceipt(requestKey, agentId, null, null,
                        "NOT_FOUND", null));
    }

    @Transactional
    public SubstrateDeploymentDtos.DeploymentResponse submit(Long agentId, SubstrateDeploymentDtos.DeployRequest request, String requester) {
        var agent = agents.findById(agentId).orElseThrow(() -> new BusinessException("智能体不存在"));
        if (agent.getLifecycleStatus() == AgentLifecycleStatus.RETIRED) throw new BusinessException("已退役智能体不能部署");
        if (!properties.isEnabled()) throw new BusinessException("Substrate Runtime 集成未启用");
        Map<String, Object> config = normalize(request);
        String configJson = json(config);
        String submissionKey = null;
        if (request.requestKey() != null) {
            if (!REQUEST_KEY.matcher(request.requestKey()).matches()) throw new BusinessException("requestKey 必须是 1–64 位字母、数字、下划线或短横线");
            submissionKey = request.requestKey();
            var existing = repository.findByAgentIdAndSubmissionKey(agentId, submissionKey);
            if (existing.isPresent()) {
                if (!callerConfig(existing.get()).equals(config)) throw new ConflictException("同一 requestKey 的部署配置已改变，请核对原部署记录");
                return response(existing.get());
            }
        }
        // Replay an existing intent before applying new-publication requirements. Legacy records
        // retain their original credential contract and port; a replay must never migrate them.
        if (!workflow.isEnabled() || workflow.hosts().isEmpty()) {
            throw new BusinessException("部署需要先启用任务派发并配置允许访问的运行入口");
        }
        if (!identityConfig.deliveryReady()) {
            throw new BusinessException("专属实例身份安全配送尚未配置，无法发布新版本");
        }
        if (!Integer.valueOf(80).equals(config.get("port"))) {
            throw new BusinessException("专属实例身份接入需要程序监听端口 80；请调整镜像或源码后发布");
        }
        boolean source = "SOURCE".equals(config.get("sourceType"));
        boolean sample = "TEMPLATE".equals(config.get("sourceType"));
        if (sample && frameworkSampleUnavailable() != null) throw new BusinessException(frameworkSampleUnavailable());
        // A source build is pinned to the digest it pushes; there is nothing to resolve before it runs.
        String image = source ? null
                : resolveImage(sample ? properties.getFrameworkSampleImage()
                        : "RUNNER".equals(config.get("sourceType")) ? runnerImage(config) : (String) config.get("image"));
        int version = repository.findFirstByAgentIdOrderByVersionDesc(agentId).map(d -> d.version + 1).orElse(1);
        SubstrateDeployment deployment = new SubstrateDeployment();
        deployment.deploymentKey = "dep-" + UUID.randomUUID();
        deployment.agentId = agentId;
        deployment.version = version;
        deployment.sourceType = (String) config.get("sourceType");
        // All new sources use the same UID-bound creation and acknowledged identity delivery.
        // Custom programs must implement the identity protocol; no shared-token fallback exists.
        config.put("identityMode", "PER_INSTANCE_V1");
        config.put("identityPublicKey", identityConfig.getDeliveryPublicKey());
        deployment.configJson = json(config);
        deployment.imageRef = image;
        deployment.atespace = properties.getDeployAtespace();
        deployment.templateName = "eap-a" + agentId + "-v" + version;
        deployment.requestedBy = requester == null || requester.isBlank() ? "console" : requester.substring(0, Math.min(128, requester.length()));
        deployment.submissionKey = submissionKey;
        deployment.status = source ? BUILDING_IMAGE : QUEUED;
        addStep(deployment, "VALIDATE", "DONE", source ? "配置校验通过，将从 " + config.get("gitUrl") + " 构建镜像"
                : "配置校验通过，镜像固定为 " + image);
        String decision = preflight(deployment, config);
        if (!"ALLOW".equals(decision)) {
            deployment.status = "DENY".equals(decision) ? BLOCKED : WAITING;
            deployment.reason = "DENY".equals(decision) ? "治理策略拒绝部署" : "部署需要治理审批；审批后请重新提交";
        }
        deployment = repository.save(deployment);
        audit(deployment.requestedBy, "SUBMIT_SUBSTRATE_DEPLOYMENT", agentId, deployment.deploymentKey + " v" + version + " " + deployment.status,
                !FAILED.equals(deployment.status) && !BLOCKED.equals(deployment.status));
        return response(deployment);
    }

    /**
     * Resumes a failed deployment from the step that failed. The template name and version are unchanged,
     * so a retry never produces a second version; a template that already exists is reused.
     */
    @Transactional
    public SubstrateDeploymentDtos.DeploymentResponse retry(Long agentId, Integer version, String requester) {
        SubstrateDeployment deployment = repository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException("部署版本不存在"));
        if (!FAILED.equals(deployment.status)) throw new BusinessException("只有失败的部署可以重试");
        List<SubstrateDeploymentDtos.Step> history = steps(deployment);
        String failedStep = history.isEmpty() ? QUEUED : history.get(history.size() - 1).step();
        deployment.status = switch (failedStep) {
            case BUILDING_IMAGE, "BUILD_IMAGE" -> BUILDING_IMAGE;
            case CREATING, "CREATE_INSTANCE" -> CREATING;
            case CONNECTING -> CONNECTING;
            case BUILDING, "GOLDEN_SNAPSHOT" -> BUILDING;
            default -> QUEUED;
        };
        deployment.reason = null;
        if (BUILDING_IMAGE.equals(deployment.status)) {
            try { builder.delete(buildName(deployment)); } catch (ImageBuilder.BuildException ignored) { }
        }
        // The golden timeout counts from submission; a retried build gets a fresh window.
        if (BUILDING.equals(deployment.status)) deployment.createdAt = Instant.now();
        addStep(deployment, "RETRY", "DONE", "由 " + requester + " 重试，从 " + deployment.status + " 继续");
        audit(requester, "RETRY_SUBSTRATE_DEPLOYMENT", agentId, deployment.deploymentKey, true);
        return response(repository.save(deployment));
    }

    /** Advances at most one deployment by one step. Called by {@link SubstrateDeploymentWorker}. */
    public void processNext() {
        if (!properties.isEnabled()) return;
        SubstrateDeployment deployment = transaction.execute(ignored ->
                repository.findFirstByStatusInOrderByUpdatedAtAscIdAsc(List.of(BUILDING_IMAGE, QUEUED, BUILDING, CREATING, CONNECTING)).orElse(null));
        if (deployment == null) return;
        try {
            switch (deployment.status) {
                case BUILDING_IMAGE -> buildStep(deployment);
                case QUEUED -> {
                    client.ensureAtespace(deployment.atespace);
                    try {
                        client.createTemplate(spec(deployment));
                    } catch (SubstrateClient.SubstrateException exception) {
                        if (!"ALREADY_EXISTS".equals(exception.code())) throw exception;
                    }
                    advance(deployment, BUILDING, "CREATE_TEMPLATE", "已创建模板 " + deployment.templateName + "，正在生成 golden 快照");
                }
                case BUILDING -> {
                    var golden = client.goldenStatus(deployment.atespace, deployment.templateName);
                    if (golden.error() != null) {
                        fail(deployment, "GOLDEN_SNAPSHOT", "golden 快照失败：" + truncate(golden.error(), 300));
                    } else if (golden.ready()) {
                        advance(deployment, CREATING, "GOLDEN_SNAPSHOT", "golden 快照已生成");
                    } else if (deployment.createdAt.plusSeconds(properties.getGoldenTimeoutSeconds()).isBefore(Instant.now())) {
                        fail(deployment, "GOLDEN_SNAPSHOT", "golden 快照超时；请检查镜像能否启动以及就绪检查路径");
                    } else {
                        touch(deployment);
                    }
                }
                case CREATING -> {
                    String instanceKey = automatic(deployment)
                            ? owned.create(deployment, creationRequest(deployment), egressHosts(config(deployment))) : createActor(deployment);
                    deployment.instanceKey = instanceKey;
                    advance(deployment, automatic(deployment) ? CONNECTING : READY, "CREATE_INSTANCE", "已创建实例 " + instanceKey + "（休眠，首个请求时唤醒）");
                }
                case CONNECTING -> {
                    owned.connect(deployment, creationRequest(deployment), deployment.requestedBy);
                    advance(deployment, READY, CONNECTING, "专属身份安全配送已确认；实例保持休眠，派发前按需续期");
                }
                default -> { }
            }
        } catch (SubstrateClient.SubstrateException exception) {
            fail(deployment, deployment.status, "Substrate " + exception.code() + "：" + truncate(exception.getMessage(), 300));
        } catch (BusinessException | ConflictException exception) {
            fail(deployment, deployment.status, "创建或专属身份未确认；原请求保留：" + truncate(exception.getMessage(), 240));
        } catch (ImageBuilder.BuildException exception) {
            fail(deployment, deployment.status, truncate(exception.getMessage(), 300));
        }
    }

    /** Starts the build once (re-issuing is harmless), then waits for it and pins the pushed digest. */
    private void buildStep(SubstrateDeployment deployment) {
        String name = buildName(deployment);
        ImageBuilder.BuildStatus status = builder.status(name);
        switch (status.state()) {
            case "MISSING" -> {
                Map<String, Object> config = config(deployment);
                builder.start(new ImageBuilder.BuildSpec(name, (String) config.get("gitUrl"), (String) config.get("gitRef"),
                        (String) config.get("contextPath"), (String) config.get("dockerfile"),
                        builder.destination(deployment.agentId, deployment.version)));
                touch(deployment);
            }
            case "SUCCEEDED" -> {
                deployment.imageRef = builder.repository(deployment.agentId) + "@" + status.digest();
                transaction.executeWithoutResult(ignored -> repository.findById(deployment.id).ifPresent(current -> {
                    current.imageRef = deployment.imageRef;
                    repository.save(current);
                }));
                advance(deployment, QUEUED, "BUILD_IMAGE", "镜像构建完成：" + deployment.imageRef);
            }
            case "FAILED" -> fail(deployment, "BUILD_IMAGE", status.reason() + lastLogLine(name));
            default -> touch(deployment);
        }
    }

    public String buildLog(Long agentId, Integer version) {
        SubstrateDeployment deployment = repository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException("部署版本不存在"));
        if (!"SOURCE".equals(deployment.sourceType)) throw new BusinessException("该版本不是源码构建");
        try {
            return builder.logTail(buildName(deployment), 200);
        } catch (ImageBuilder.BuildException exception) {
            throw new BusinessException(exception.getMessage());
        }
    }

    private String lastLogLine(String name) {
        try {
            // kaniko prints its usage text after a fatal error; the "Error:" line is the actual cause.
            String[] lines = builder.logTail(name, 80).replaceAll("\u001B\\[[;\\d]*m", "").strip().split("\n");
            String cause = "";
            for (String line : lines) {
                if (line.strip().startsWith("Error:") || line.contains("error building image")) cause = line.strip();
            }
            if (cause.isEmpty()) cause = lines[lines.length - 1].strip();
            return cause.isEmpty() ? "" : "：" + truncate(cause, 200);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String buildName(SubstrateDeployment deployment) {
        return "eap-build-a" + deployment.agentId + "-v" + deployment.version;
    }

    public List<SubstrateDeploymentDtos.InstanceResponse> instances(Long agentId) {
        return runtimes.findByAgentId(agentId).stream()
                .filter(runtime -> SubstrateActorRef.isSubstrate(runtime.getProtocol()) && runtime.getStatus() != RuntimeStatus.OFFLINE)
                .map(runtime -> {
                    SubstrateActorRef ref = SubstrateActorRef.parse(runtime.getEndpoint());
                    Matcher name = INSTANCE_NAME.matcher(ref.name());
                    Integer version = runtime.getImportedActorUid() == null && name.matches() ? Integer.valueOf(name.group(2)) : null;
                    try {
                        return new SubstrateDeploymentDtos.InstanceResponse(runtime.getInstanceKey(), ref.name(), version,
                                client.get(ref).state(), runtime.getStatus().name(), null);
                    } catch (SubstrateClient.SubstrateException exception) {
                        return new SubstrateDeploymentDtos.InstanceResponse(runtime.getInstanceKey(), ref.name(), version,
                                "UNKNOWN", runtime.getStatus().name(), exception.code());
                    }
                }).toList();
    }

    /** Creates another session instance from the newest ready version. */
    public SubstrateDeploymentDtos.InstanceResponse createInstance(Long agentId, String requestKey, String requester) {
        String original = owned.existingDeployment(agentId, requestKey);
        SubstrateDeployment deployment = (original == null ? repository.findFirstByAgentIdAndStatusOrderByVersionDesc(agentId, READY)
                : repository.findByDeploymentKey(original))
                .orElseThrow(() -> new BusinessException("该智能体还没有部署成功的版本"));
        try {
            if(!automatic(deployment))throw new BusinessException("旧版本需要部署新的专属身份版本后再创建实例");
            String key = owned.create(deployment, requestKey, egressHosts(config(deployment)));
            owned.connect(deployment, requestKey, requester);
            audit(requester, "CREATE_SUBSTRATE_INSTANCE", agentId, key, true);
            return new SubstrateDeploymentDtos.InstanceResponse(key, key.substring(key.indexOf('/') + 1), deployment.version,
                    "SUSPENDED", RuntimeStatus.ONLINE.name(), null);
        } catch (SubstrateClient.SubstrateException exception) {
            throw new BusinessException("创建实例失败（" + exception.code() + "）：" + truncate(exception.getMessage(), 200));
        }
    }

    public void deleteInstance(Long agentId, String name, String requester) {
        AgentRuntimeInstance runtime = substrateRuntime(agentId, name);
        if(runtime.hasBoundIdentity()) { connections.checkIdentity(runtime);connections.revoke(runtime.getInstanceKey(),requester); }
        try {
            client.deleteActor(SubstrateActorRef.parse(runtime.getEndpoint()));
        } catch (SubstrateClient.SubstrateException exception) {
            throw new BusinessException("删除实例失败（" + exception.code() + "）：" + truncate(exception.getMessage(), 200));
        }
        // The actor no longer exists, so its registration goes too: an OFFLINE row would keep raising
        // "runtime offline" work items and inflate instance counts. The audit log keeps the history.
        if(runtime.hasBoundIdentity()) {
            // Preserve the identity tombstone, tool grants, effects and original creation intent.
            transaction.executeWithoutResult(s->{
                var current=runtimes.lockForScopedTools(runtime.getInstanceKey()).orElseThrow();
                current.markOffline();runtimes.saveAndFlush(current);
            });
        } else runtimes.delete(runtime);
        audit(requester, "DELETE_SUBSTRATE_INSTANCE", agentId, runtime.getInstanceKey(), true);
    }

    /** Console history is explicit and belongs to this request, never to the shared actor. */
    public SubstrateDeploymentDtos.ChatResponse chat(Long agentId, String name, String message) {
        return chat(agentId, name, message, List.of());
    }

    public SubstrateDeploymentDtos.ChatResponse chat(Long agentId, String name, String message,
            List<SubstrateDeploymentDtos.ChatMessage> history) {
        return chatOnce(agentId, name, message, history, trace -> {});
    }

    // Console HTTP callers must enter via ChatSubmissionService's durable admission.
    SubstrateDeploymentDtos.ChatResponse chatOnce(Long agentId, String name, String message,
            List<SubstrateDeploymentDtos.ChatMessage> history, java.util.function.Consumer<String> beforeDispatch) {
        history = validateChatHistory(history);
        AgentRuntimeInstance runtime = substrateRuntime(agentId, name);
        SubstrateActorRef ref = SubstrateActorRef.parse(runtime.getEndpoint());
        if(runtime.hasBoundIdentity()) {
            if(!connections.executable(runtime))throw new BusinessException("实例接入已撤销或授权期已过，未派发对话");
            connections.checkIdentity(runtime);
        }
        try (var occupancy = work.start(runtime.getEndpoint(), "CHAT")) {
            boolean known = true;
            try {
            String observation = coverage == null ? null : coverage.enrollChat(runtime);
            try {
                client.resume(ref);
            } catch (SubstrateClient.SubstrateException exception) {
                if (observation != null) coverage.runtimeReceipt(observation, "FAILED", null);
                throw new BusinessException("RESOURCE_EXHAUSTED".equals(exception.code())
                        ? "没有空闲 worker，请先挂起其他实例或扩容" : "唤醒实例失败（" + exception.code() + "）");
            }
            boolean dispatched = false;
            try {
                String dispatchToken=runtime.hasBoundIdentity()?connections.dispatchToken(runtime):workflow.getToken();
                HttpRequest stateRequest = HttpRequest.newBuilder(URI.create(properties.getRouterUrl().replaceAll("/+$", "") + "/eap/state"))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + dispatchToken)
                        .header(SubstrateActorRef.ROUTING_HEADER, ref.routingValue()).GET().build();
                HttpResponse<String> state = http.send(stateRequest, HttpResponse.BodyHandlers.ofString());
                if (state.statusCode() != 200 || !"EXPLICIT_V1".equals(mapper.readTree(state.body()).path("conversationMode").asText()))
                    throw new BusinessException("此实例尚未支持对话隔离，请发布新版 Runner 后使用新实例试运行；未调用模型");
                String rawTrace = observation == null ? null : coverage.runtimeDispatch(observation, runtime, Instant.now().plusSeconds(150));
                Map<String,Object> envelope = new LinkedHashMap<>(); envelope.put("message", message);
                envelope.put("history", history);
                if (rawTrace != null) envelope.put("traceId", rawTrace);
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getRouterUrl().replaceAll("/+$", "") + "/chat"))
                        .timeout(Duration.ofSeconds(150))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + dispatchToken)
                        .header(SubstrateActorRef.ROUTING_HEADER, ref.routingValue())
                        .POST(HttpRequest.BodyPublishers.ofString(json(envelope))).build();
                beforeDispatch.accept(rawTrace != null && runtime.supportsMachineIdentity()
                        ? com.eap.platform.registry.RuntimeScopedTelemetry.trace(runtime.getInstanceKey(), rawTrace) : rawTrace);
                dispatched = true; known = false;
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) throw new BusinessException("该实例不支持对话试运行（仅平台通用 Agent 提供 /chat）");
                if (response.statusCode() != 200) {
                    throw new BusinessException("Agent 返回 HTTP " + response.statusCode() + "，请核对接入状态");
                }
                JsonNode body = mapper.readTree(response.body());
                if (!body.path("reply").isTextual() || body.path("reply").asText().isBlank()
                        || !body.path("turns").isIntegralNumber() || body.path("turns").asInt() != history.size() / 2 + 1)
                    throw new BusinessException("实例返回的对话回执不完整，请查询原提交");
                if (observation != null) coverage.runtimeReceipt(observation, "SUCCEEDED", body.path("traceId").isTextual() ? body.path("traceId").asText() : null);
                known = true;
                return new SubstrateDeploymentDtos.ChatResponse(body.path("reply").asText(), body.path("turns").asInt(), runtime.getInstanceKey());
            } catch (BusinessException exception) {
                if (observation != null) coverage.runtimeReceipt(observation, dispatched ? "EXECUTION_UNKNOWN" : "FAILED", null);
                throw exception;
            } catch (InterruptedException exception) {
                if (observation != null) coverage.runtimeReceipt(observation, dispatched ? "EXECUTION_UNKNOWN" : "FAILED", null);
                Thread.currentThread().interrupt();
                throw new BusinessException("试运行被中断");
            } catch (Exception exception) {
                if (observation != null) coverage.runtimeReceipt(observation, dispatched ? "EXECUTION_UNKNOWN" : "FAILED", null);
                throw new BusinessException("试运行请求失败：" + truncate(String.valueOf(exception.getMessage()), 200));
            }
            } finally { occupancy.complete(known); }
        }
    }

    static List<SubstrateDeploymentDtos.ChatMessage> validateChatHistory(List<SubstrateDeploymentDtos.ChatMessage> input) {
        if (input == null) return List.of();
        if (input.size() > 40 || input.size() % 2 != 0) throw new BusinessException("对话历史必须为完整问答，最多20轮");
        int bytes = 0;
        for (int i = 0; i < input.size(); i++) {
            var item = input.get(i);
            if (item == null || !(i % 2 == 0 ? "user" : "assistant").equals(item.role())
                    || item.content() == null || item.content().isBlank()) throw new BusinessException("对话历史格式无效");
            bytes += item.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > 65536) throw new BusinessException("对话历史超过64KB，请开始新对话");
        }
        return List.copyOf(input);
    }

    private String createActor(SubstrateDeployment deployment) {
        String name = "a" + deployment.agentId + "-v" + deployment.version + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        SubstrateActorRef ref = new SubstrateActorRef(deployment.atespace, name);
        client.createActor(ref, deployment.templateName);
        try {
            client.setEgressHostnames(ref, egressHosts(config(deployment)));
        } catch (SubstrateClient.SubstrateException exception) {
            // An actor without its intended policy must not linger: its egress would be denied or wrong.
            try { client.deleteActor(ref); } catch (SubstrateClient.SubstrateException ignored) { }
            throw exception;
        }
        registry.registerRuntime(deployment.agentId, new AgentRegistryDtos.RuntimeInstanceRequest(
                ref.routingValue(), "substrate://" + ref.routingValue(), SubstrateActorRef.PROTOCOL, false));
        return ref.routingValue();
    }

    private AgentRuntimeInstance substrateRuntime(Long agentId, String name) {
        return runtimes.findByInstanceKey(properties.getDeployAtespace() + "/" + name)
                .filter(runtime -> runtime.getImportedActorUid() == null)
                .filter(runtime -> agentId.equals(runtime.getAgentId()) && SubstrateActorRef.isSubstrate(runtime.getProtocol())
                        && runtime.getStatus() != RuntimeStatus.OFFLINE)
                .orElseThrow(() -> new BusinessException("实例不存在"));
    }

    private Map<String, Object> normalize(SubstrateDeploymentDtos.DeployRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        String source = upper(request.sourceType());
        if (!Set.of("RUNNER", "IMAGE", "SOURCE", "TEMPLATE").contains(source)) {
            throw new BusinessException("部署来源只能是 RUNNER、IMAGE、SOURCE 或 TEMPLATE（受控框架样例）");
        }
        config.put("sourceType", source);
        if ("TEMPLATE".equals(source)) {
            var sample = FrameworkSamples.require(upper(request.runnerFramework()));
            if (request.image() != null || request.command() != null || request.port() != null || request.readinessPath() != null
                    || request.systemPrompt() != null || request.model() != null || request.maxTokens() != null
                    || request.thinkingMode() != null || request.runnerToolMode() != null || request.gitUrl() != null
                    || request.gitRef() != null || request.contextPath() != null || request.dockerfile() != null
                    || request.env() != null && !request.env().isEmpty()
                    || request.egressHosts() != null && !request.egressHosts().isEmpty())
                throw new BusinessException("受控框架样例的镜像、命令、模型、环境变量和出站配置由平台固定");
            if (request.sandboxClass() != null && !"GVISOR".equals(upper(request.sandboxClass())))
                throw new BusinessException("受控框架样例当前仅支持 gVisor");
            config.put("runnerFramework", sample.key());
            config.put("frameworkVersion", sample.frameworkVersion());
            config.put("port", 80);
            config.put("readinessPath", "/readyz");
            config.put("command", List.of(sample.command()));
        } else if ("RUNNER".equals(source)) {
            String framework = request.runnerFramework() == null ? "EAP_NATIVE" : upper(request.runnerFramework());
            if (!Set.of("EAP_NATIVE", "KAGENT_ADK").contains(framework)) throw new BusinessException("运行框架无效");
            // Keep the old shape for native callers and existing submission keys.
            if ("KAGENT_ADK".equals(framework)) config.put("runnerFramework", framework);
            else if (properties.getRunnerImage().isBlank()) throw new BusinessException("平台通用 Agent 镜像未配置");
            if (request.runnerToolMode() != null) {
                String mode = upper(request.runnerToolMode());
                if (!"KAGENT_ADK".equals(framework) || !Set.of("NONE", "CALCULATE_SUM_V1").contains(mode))
                    throw new BusinessException("工具模式仅支持 Kagent 的受控求和");
                if (!"NONE".equals(mode)) config.put("runnerToolMode", mode);
            }
            String prompt = request.systemPrompt() == null ? "" : request.systemPrompt().trim();
            if (prompt.isEmpty()) throw new BusinessException("请填写系统提示词");
            String model = request.model() == null ? "" : request.model().trim();
            if (!MODEL.matcher(model).matches()) throw new BusinessException("模型名称无效");
            config.put("systemPrompt", prompt);
            config.put("model", model);
            // Omitted fields retain the legacy config shape, including idempotent request comparison.
            if (request.maxTokens() != null) {
                if (request.maxTokens() < 1 || request.maxTokens() > 8192) throw new BusinessException("输出上限必须在 1–8192 之间");
                config.put("maxTokens", request.maxTokens());
            }
            if (request.thinkingMode() != null) {
                String thinking = upper(request.thinkingMode());
                if (!Set.of("PROVIDER_DEFAULT", "DISABLED").contains(thinking)) throw new BusinessException("思考模式无效");
                if ("DISABLED".equals(thinking) && !Set.of("deepseek-flash", "deepseek-pro").contains(model)) {
                    throw new BusinessException("关闭思考仅支持 deepseek-flash 和 deepseek-pro");
                }
                config.put("thinkingMode", thinking);
            }
            config.put("port", 80);
            config.put("readinessPath", "/readyz");
            config.put("command", List.of());
        } else {
            if (request.runnerToolMode() != null) throw new BusinessException("工具模式仅适用于 Kagent 零代码配置");
            if (request.runnerFramework() != null) throw new BusinessException("运行框架仅适用于零代码配置");
            if (request.maxTokens() != null || request.thinkingMode() != null) throw new BusinessException("模型参数仅适用于零代码配置");
            if ("SOURCE".equals(source)) {
                if (!builder.enabled()) throw new BusinessException("源码构建未启用");
                String gitUrl = request.gitUrl() == null ? "" : request.gitUrl().trim().replaceAll("/+$", "");
                Matcher git = GIT_URL.matcher(gitUrl);
                if (!git.matches() || "http".equals(git.group(1))
                        && !properties.buildHttpGitHosts().contains(git.group(2).toLowerCase(Locale.ROOT))) {
                    throw new BusinessException("Git 地址必须是不含账号、参数的 https 地址（http 仅限已登记的内部 Git 服务）");
                }
                String gitRef = request.gitRef() == null || request.gitRef().isBlank() ? "main" : request.gitRef().trim();
                if (!GIT_REF.matcher(gitRef).matches()) throw new BusinessException("分支、标签或提交号格式无效");
                String contextPath = request.contextPath() == null ? "" : request.contextPath().trim().replaceAll("^\\./|/+$", "");
                String dockerfile = request.dockerfile() == null || request.dockerfile().isBlank() ? "Dockerfile" : request.dockerfile().trim();
                if (!REPO_PATH.matcher(contextPath).matches() || !REPO_PATH.matcher(dockerfile).matches() || dockerfile.isEmpty()) {
                    throw new BusinessException("构建目录或 Dockerfile 路径无效");
                }
                config.put("gitUrl", gitUrl);
                config.put("gitRef", gitRef);
                config.put("contextPath", contextPath);
                config.put("dockerfile", dockerfile);
            }
            String image = "SOURCE".equals(source) ? "" : request.image() == null ? "" : request.image().trim();
            if (!"SOURCE".equals(source) && image.isEmpty()) throw new BusinessException("请填写镜像地址");
            int port = request.port() == null ? 80 : request.port();
            if (port < 1 || port > 65535) throw new BusinessException("端口必须在 1–65535 之间");
            String path = request.readinessPath() == null || request.readinessPath().isBlank() ? "/" : request.readinessPath().trim();
            if (!PROBE_PATH.matcher(path).matches()) throw new BusinessException("就绪检查路径必须以 / 开头，且不含查询参数");
            if (!image.isEmpty()) config.put("image", image);
            config.put("port", port);
            config.put("readinessPath", path);
            config.put("command", request.command() == null ? List.of() : request.command().stream().filter(Objects::nonNull).toList());
        }
        Map<String, String> env = new TreeMap<>();
        if (request.env() != null) {
            request.env().forEach((name, value) -> {
                String key = name == null ? "" : name.trim();
                if (!ENV_NAME.matcher(key).matches()) throw new BusinessException("环境变量名无效：" + key);
                if (key.startsWith("EAP_") || key.startsWith("AGENT_")) throw new BusinessException("EAP_、AGENT_ 前缀由平台保留：" + key);
                if (SECRET_NAME.matcher(key).matches()) {
                    throw new BusinessException("不能通过环境变量传递密钥（" + key + "）：模板以明文保存；模型调用请经 EAP 网关");
                }
                env.put(key, value == null ? "" : value);
            });
        }
        config.put("env", env);
        String sandbox = request.sandboxClass() == null ? "GVISOR" : upper(request.sandboxClass());
        if (!Set.of("GVISOR", "MICROVM").contains(sandbox)) throw new BusinessException("沙箱类型只能是 GVISOR 或 MICROVM");
        config.put("sandboxClass", sandbox);
        String size = request.size() == null ? "SMALL" : upper(request.size());
        if (!SIZES.containsKey(size)) throw new BusinessException("资源规格只能是 " + SIZES.keySet());
        config.put("size", size);
        List<String> hosts = new ArrayList<>();
        if (request.egressHosts() != null) {
            for (String host : request.egressHosts()) {
                String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
                if (value.isEmpty()) continue;
                if (!HOST.matcher(value).matches()) throw new BusinessException("出站主机名无效：" + value);
                if (!hosts.contains(value) && !value.equals(properties.getGatewayHost())) hosts.add(value);
            }
        }
        config.put("egressHosts", hosts);
        return config;
    }

    /** Each extra egress host is a data-access decision; the image itself is a deployment policy check. */
    private String preflight(SubstrateDeployment deployment, Map<String, Object> config) {
        safety.createSession(new SafetyDtos.SessionRequest(deployment.deploymentKey, deployment.agentId,
                "部署 Agent 版本 v" + deployment.version));
        List<SafetyDtos.SafetyEventRequest> events = new ArrayList<>();
        events.add(new SafetyDtos.SafetyEventRequest(deployment.deploymentKey, deployment.agentId, null, SafetyEventType.POLICY_CHECK,
                "deploy_agent", truncate(deployment.imageRef != null ? deployment.imageRef : (String) config.get("gitUrl"), 256),
                "Substrate 部署：" + deployment.sourceType + " / " + config.get("sandboxClass"),
                json(Map.of("image", Objects.toString(deployment.imageRef, ""), "source", Objects.toString(config.get("gitUrl"), ""),
                        "size", config.get("size"), "sandbox", config.get("sandboxClass")))));
        for (Object host : (List<?>) config.get("egressHosts")) {
            events.add(new SafetyDtos.SafetyEventRequest(deployment.deploymentKey, deployment.agentId, null, SafetyEventType.DATA_ACCESS,
                    "egress_allow", String.valueOf(host), "允许 Agent 出站访问 " + host, null));
        }
        String result = "ALLOW";
        for (var event : events) {
            SafetyDecision decision = safety.evaluate(event).decision().decision();
            addStep(deployment, "PREFLIGHT", decision.name(), event.action() + " → " + event.target());
            if (decision == SafetyDecision.DENY) return "DENY";
            if (decision != SafetyDecision.ALLOW) result = "ASK";
        }
        return result;
    }

    private SubstrateClient.TemplateSpec spec(SubstrateDeployment deployment) {
        Map<String, Object> config = config(deployment);
        Map<String, String> env = new TreeMap<>();
        @SuppressWarnings("unchecked") Map<String, String> userEnv = (Map<String, String>) config.getOrDefault("env", Map.of());
        env.putAll(userEnv);
        // Platform variables are injected last so user input can never override them.
        env.put("EAP_AGENT_ID", String.valueOf(deployment.agentId));
        if(automatic(deployment)) {
            env.put("EAP_IDENTITY_PUBLIC_KEY",(String)config.get("identityPublicKey"));
            env.put("EAP_IDENTITY_DIRECTORY","/run/eap-identity");
            env.put("EAP_TOOLS_ENABLED","true");
        } else env.put("EAP_TASK_TOKEN", workflow.getToken());
        if (!automatic(deployment) && !observation.getIngestionToken().isBlank()) env.put("EAP_OBSERVABILITY_TOKEN", observation.getIngestionToken());
        if (!properties.getGatewayUrl().isBlank()) {
            env.put("EAP_GATEWAY_URL", properties.getGatewayUrl());
            if(!automatic(deployment))env.put("EAP_GATEWAY_TOKEN", properties.getGatewayToken());
        }
        if ("RUNNER".equals(deployment.sourceType)) {
            env.put("AGENT_MODEL", (String) config.get("model"));
            if (config.containsKey("runnerToolMode")) env.put("EAP_KAGENT_TOOL_MODE", (String) config.get("runnerToolMode"));
            env.put("AGENT_SYSTEM_PROMPT", (String) config.get("systemPrompt"));
            if (config.containsKey("maxTokens")) env.put("AGENT_MAX_TOKENS", String.valueOf(config.get("maxTokens")));
            if (config.containsKey("thinkingMode")) env.put("AGENT_THINKING_MODE", (String) config.get("thinkingMode"));
        }
        String[] size = SIZES.get((String) config.get("size"));
        @SuppressWarnings("unchecked") List<String> command = (List<String>) config.getOrDefault("command", List.of());
        return new SubstrateClient.TemplateSpec(deployment.atespace, deployment.templateName, deployment.imageRef, command, env,
                (String) config.get("readinessPath"), ((Number) config.get("port")).intValue(), size[0], size[1],
                "MICROVM".equals(config.get("sandboxClass")), properties.workerLabels(),
                properties.getSnapshotLocation().replaceAll("/*$", "/") + deployment.agentId + "/", automatic(deployment));
    }

    private boolean automatic(SubstrateDeployment d) { return "PER_INSTANCE_V1".equals(config(d).get("identityMode")); }
    private String creationRequest(SubstrateDeployment d) { return UUID.fromString(d.deploymentKey.substring(4)).toString(); }
    private Map<String,Object> callerConfig(SubstrateDeployment d) {
        var c=new LinkedHashMap<>(config(d));c.remove("identityMode");c.remove("identityPublicKey");return c;
    }

    private String runnerImage(Map<String, Object> config) {
        if ("KAGENT_ADK".equals(config.get("runnerFramework"))) {
            if (!properties.kagentRunnerAvailable()) throw new BusinessException("Kagent ADK 运行程序尚未配置固定镜像");
            return properties.getKagentRunnerImage();
        }
        return properties.getRunnerImage();
    }

    private List<String> egressHosts(Map<String, Object> config) {
        List<String> hosts = new ArrayList<>();
        if (!properties.getGatewayHost().isBlank()) hosts.add(properties.getGatewayHost());
        for (Object host : (List<?>) config.getOrDefault("egressHosts", List.of())) hosts.add(String.valueOf(host));
        return hosts;
    }

    /** Pins an image by digest. Tags are only resolved against the cluster-local registry. */
    String resolveImage(String image) {
        String value = image == null ? "" : image.trim();
        if (DIGEST_REF.matcher(value).matches()) return value;
        String prefix = properties.getLocalRegistryPrefix();
        if (!value.startsWith(prefix) || properties.getLocalRegistryUrl().isBlank()) {
            throw new BusinessException("镜像必须以 @sha256 摘要固定，或位于集群本地仓库 " + prefix);
        }
        Matcher ref = TAG_REF.matcher(value.substring(prefix.length()));
        if (!ref.matches()) throw new BusinessException("镜像地址格式无效");
        String repository = ref.group(1);
        String tag = ref.group(2) == null ? "latest" : ref.group(2);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getLocalRegistryUrl().replaceAll("/+$", "")
                            + "/v2/" + repository + "/manifests/" + tag))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, "
                            + "application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            String digest = response.headers().firstValue("Docker-Content-Digest").orElse("");
            if (response.statusCode() != 200 || !digest.matches("sha256:[a-f0-9]{64}")) {
                throw new BusinessException("本地仓库中找不到镜像 " + value + "（HTTP " + response.statusCode() + "）");
            }
            return prefix + repository + "@" + digest;
        } catch (BusinessException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("查询镜像摘要被中断");
        } catch (Exception exception) {
            throw new BusinessException("无法访问集群本地镜像仓库");
        }
    }

    private void advance(SubstrateDeployment deployment, String status, String step, String detail) {
        transaction.executeWithoutResult(ignored -> {
            SubstrateDeployment current = repository.findById(deployment.id).orElseThrow();
            addStep(current, step, "DONE", detail);
            current.status = status;
            current.instanceKey = deployment.instanceKey != null ? deployment.instanceKey : current.instanceKey;
            current.updatedAt = Instant.now();
            repository.save(current);
        });
        if (READY.equals(status)) audit("system", "SUBSTRATE_DEPLOYMENT_READY", deployment.agentId, deployment.deploymentKey, true);
    }

    private void fail(SubstrateDeployment deployment, String step, String reason) {
        transaction.executeWithoutResult(ignored -> {
            SubstrateDeployment current = repository.findById(deployment.id).orElseThrow();
            addStep(current, step, "FAILED", reason);
            current.status = FAILED;
            current.reason = truncate(reason, 512);
            current.updatedAt = Instant.now();
            repository.save(current);
        });
        audit("system", "SUBSTRATE_DEPLOYMENT_FAILED", deployment.agentId, deployment.deploymentKey + " " + reason, false);
    }

    /** Moves a still-building deployment to the back of the queue so others advance too. */
    private void touch(SubstrateDeployment deployment) {
        transaction.executeWithoutResult(ignored -> repository.findById(deployment.id).ifPresent(current -> {
            current.updatedAt = Instant.now();
            repository.save(current);
        }));
    }

    private void addStep(SubstrateDeployment deployment, String step, String status, String detail) {
        List<SubstrateDeploymentDtos.Step> steps = new ArrayList<>(steps(deployment));
        steps.add(new SubstrateDeploymentDtos.Step(step, status, truncate(detail, 300), Instant.now()));
        deployment.stepsJson = json(steps);
    }

    private List<SubstrateDeploymentDtos.Step> steps(SubstrateDeployment deployment) {
        try {
            return mapper.readValue(deployment.stepsJson, new TypeReference<List<SubstrateDeploymentDtos.Step>>() { });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> config(SubstrateDeployment deployment) {
        try {
            return mapper.readValue(deployment.configJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception exception) {
            throw new BusinessException("部署配置无法读取");
        }
    }

    private SubstrateDeploymentDtos.DeploymentResponse response(SubstrateDeployment deployment) {
        return new SubstrateDeploymentDtos.DeploymentResponse(deployment.deploymentKey, deployment.agentId, deployment.version,
                deployment.sourceType, deployment.status, deployment.imageRef, deployment.templateName, deployment.instanceKey,
                deployment.reason, deployment.requestedBy, steps(deployment), metadata(deployment), deployment.createdAt, deployment.updatedAt);
    }

    private Map<String, Object> metadata(SubstrateDeployment deployment) {
        Map<String, Object> stored = config(deployment);
        Map<String, Object> visible = new LinkedHashMap<>();
        // Configuration is retained intact for execution; general responses expose only console metadata.
        for (String key : List.of("sourceType", "runnerFramework", "frameworkVersion", "runnerToolMode",
                "model", "maxTokens", "thinkingMode", "port", "sandboxClass", "size", "identityMode")) {
            Object value = stored.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) visible.put(key, value);
        }
        return visible;
    }

    private void audit(String actor, String action, Long agentId, String detail, boolean success) {
        AuditLog log = new AuditLog();
        log.setActor(actor == null || actor.isBlank() ? "console" : actor);
        log.setAction(action);
        log.setResourceType("AGENT");
        log.setResourceId(String.valueOf(agentId));
        log.setAfterJson(json(Map.of("detail", truncate(detail, 500))));
        log.setResult(success ? "SUCCESS" : "FAILED");
        log.setTraceId(TraceContext.traceId());
        log.setCreatedAt(Instant.now());
        audits.save(log);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new BusinessException("部署数据无法序列化");
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max);
    }
}
