package com.eap.platform.control;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import com.eap.platform.registry.RuntimeLeaseProperties;
import com.eap.platform.substrate.SubstrateActorRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentControlService {
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "PAUSE",
            "RESUME",
            "RELOAD_POLICY",
            "UPDATE_CONFIG",
            "RUN_HEALTH_CHECK",
            "SET_RATE_LIMIT",
            "REVERT"
    );

    private final AgentControlCommandRepository commandRepository;
    private final AgentRepository agentRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ControlDispatchProperties dispatchProperties;
    private final AgentControlAdapter httpAdapter;
    private final AgentControlAdapter substrateAdapter;
    private final RuntimeLeaseProperties leaseProperties;

    public AgentControlService(
            AgentControlCommandRepository commandRepository,
            AgentRepository agentRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            AuditLogRepository auditLogRepository,
            ControlDispatchProperties dispatchProperties,
            @Qualifier("httpAgentControlAdapter") AgentControlAdapter httpAdapter,
            @Qualifier("substrateControlAdapter") AgentControlAdapter substrateAdapter,
            RuntimeLeaseProperties leaseProperties
    ) {
        this.commandRepository = commandRepository;
        this.agentRepository = agentRepository;
        this.runtimeRepository = runtimeRepository;
        this.auditLogRepository = auditLogRepository;
        this.dispatchProperties = dispatchProperties;
        this.httpAdapter = httpAdapter;
        this.substrateAdapter = substrateAdapter;
        this.leaseProperties = leaseProperties;
    }

    @Transactional(readOnly = true)
    public List<AgentControlDtos.CommandResponse> recent() {
        return commandRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(AgentControlDtos::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentControlDtos.Targets targets(Long agentId) {
        var agent = agentRepository.findById(agentId).orElseThrow(() -> new BusinessException("智能体不存在"));
        var cutoff = leaseProperties.cutoff();
        var rows = runtimeRepository.findByAgentId(agentId).stream().map(runtime -> {
            boolean substrate = SubstrateActorRef.isSubstrate(runtime.getProtocol());
            RuntimeStatus status = leaseProperties.effectiveStatus(runtime, cutoff);
            String reason = agent.getLifecycleStatus() == AgentLifecycleStatus.RETIRED ? "智能体已退役"
                    : "SUBSTRATE_TASK".equals(runtime.getProtocol()) ? "任务实例请在任务记录操作，避免中断在途执行"
                    : !substrate && !"HTTP".equalsIgnoreCase(runtime.getProtocol()) ? "实例协议不支持控制命令"
                    : status == RuntimeStatus.OFFLINE ? "实例离线或心跳已过期"
                    : !substrate && status != RuntimeStatus.ONLINE ? "实例当前不可派发" : null;
            List<String> supported = reason != null ? List.of() : substrate
                    ? (runtime.getImportedActorUid() == null
                        ? List.of("RUN_HEALTH_CHECK", "PAUSE", "RESUME", "REVERT")
                        : List.of("RUN_HEALTH_CHECK", "PAUSE", "RESUME"))
                    : List.of("RUN_HEALTH_CHECK", "PAUSE", "RESUME", "RELOAD_POLICY", "UPDATE_CONFIG", "SET_RATE_LIMIT");
            return new AgentControlDtos.Target(runtime.getInstanceKey(), status.name(), supported, reason);
        }).toList();
        return new AgentControlDtos.Targets(rows, dispatchProperties.isEnabled(), dispatchProperties.getMaxAttempts());
    }

    @Transactional
    public AgentControlDtos.CommandResponse issue(AgentControlDtos.CommandRequest request) {
        String commandType = request.commandType().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_COMMANDS.contains(commandType)) {
            throw new BusinessException("不支持的控制命令");
        }
        var agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new BusinessException("智能体不存在"));
        if (agent.getLifecycleStatus() == AgentLifecycleStatus.RETIRED) {
            throw new BusinessException("已退役智能体不能接收控制命令");
        }

        AgentControlCommand command = new AgentControlCommand();
        command.commandKey = "CTRL-" + UUID.randomUUID().toString().substring(0, 8);
        command.agentId = request.agentId();
        command.commandType = commandType;
        command.payloadJson = request.payloadJson();
        command.issuedBy = request.issuedBy() == null || request.issuedBy().isBlank() ? "platform-admin" : request.issuedBy();
        command.dispatchMode = "CONTROL_PLANE_OUTBOX";
        command.runtimeInstanceKey = request.runtimeInstanceKey() == null || request.runtimeInstanceKey().isBlank()
                ? null : request.runtimeInstanceKey();
        command.status = ControlCommandStatus.QUEUED;
        command.attemptCount = 0;
        command.resultMessage = dispatchProperties.isEnabled()
                ? "控制命令已写入 outbox，准备投递到在线 Agent Runtime。"
                : "控制命令已写入 outbox；HTTP Runtime 适配器未启用。";
        command = commandRepository.save(command);
        if (dispatchProperties.isEnabled()) dispatch(command);
        writeAudit(command, command.issuedBy, "ISSUE_CONTROL_COMMAND");
        return AgentControlDtos.from(command);
    }

    @Transactional
    public AgentControlDtos.CommandResponse retry(String commandKey, String retryActor) {
        AgentControlCommand command = commandRepository.findForRetry(commandKey)
                .orElseThrow(() -> new BusinessException("控制命令不存在"));
        var agent = agentRepository.findById(command.agentId)
                .orElseThrow(() -> new BusinessException("智能体不存在"));
        if (agent.getLifecycleStatus() == AgentLifecycleStatus.RETIRED) {
            throw new BusinessException("已退役智能体不能接收控制命令");
        }
        if (command.status == ControlCommandStatus.ACKED) {
            throw new BusinessException("已 ACK 的控制命令不能重试");
        }
        if ((command.attemptCount == null ? 0 : command.attemptCount) >= dispatchProperties.getMaxAttempts()) {
            throw new BusinessException("控制命令已达到最大重试次数 " + dispatchProperties.getMaxAttempts());
        }
        command.status = ControlCommandStatus.QUEUED;
        command.resultMessage = dispatchProperties.isEnabled() ? "正在重试控制命令。" : "控制命令已回到 outbox；HTTP Runtime 适配器未启用。";
        command = commandRepository.save(command);
        if (dispatchProperties.isEnabled()) dispatch(command);
        writeAudit(command, retryActor == null || retryActor.isBlank() ? "console" : retryActor, "RETRY_CONTROL_COMMAND");
        return AgentControlDtos.from(command);
    }

    private void dispatch(AgentControlCommand command) {
        command.attemptCount = (command.attemptCount == null ? 0 : command.attemptCount) + 1;
        // A command issued for a specific instance never falls back to another instance.
        AgentRuntimeInstance runtime = command.runtimeInstanceKey != null
                ? runtimeRepository.findByInstanceKey(command.runtimeInstanceKey)
                        .filter(candidate -> command.agentId.equals(candidate.getAgentId()) && (SubstrateActorRef.isSubstrate(candidate.getProtocol())
                                ? candidate.getStatus() != RuntimeStatus.OFFLINE
                                : leaseProperties.effectiveStatus(candidate, leaseProperties.cutoff()) == RuntimeStatus.ONLINE))
                        .orElse(null)
                : runtimeRepository
                .findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(command.agentId, RuntimeStatus.ONLINE, leaseProperties.cutoff())
                .or(() -> substrateRuntime(command.agentId))
                .orElse(null);
        if (runtime == null) {
            command.status = ControlCommandStatus.FAILED;
            command.dispatchMode = "HTTP_RUNTIME";
            command.resultMessage = "所选智能体没有可用的运行实例，或指定实例已离线、心跳过期。";
            commandRepository.save(command);
            return;
        }
        boolean substrate = SubstrateActorRef.isSubstrate(runtime.getProtocol());
        command.runtimeInstanceKey = runtime.getInstanceKey();
        command.dispatchMode = substrate ? SubstrateControlAdapter.DISPATCH_MODE : "HTTP_RUNTIME";
        command.dispatchedAt = Instant.now();
        if (!substrate && "REVERT".equals(command.commandType)) {
            command.status = ControlCommandStatus.FAILED;
            command.resultMessage = "REVERT 仅适用于 Substrate Runtime。";
            commandRepository.save(command);
            return;
        }
        try {
            AgentControlAdapter.DispatchResult result = (substrate ? substrateAdapter : httpAdapter).dispatch(command, runtime);
            command.status = result.status();
            command.dispatchMode = result.dispatchMode();
            command.resultMessage = result.resultMessage();
            command.httpStatus = result.httpStatus();
            command.acknowledgedAt = result.acknowledgedAt();
        } catch (RuntimeException exception) {
            command.status = ControlCommandStatus.FAILED;
            command.resultMessage = exception.getMessage() == null ? "控制适配器执行失败" : exception.getMessage();
        }
        commandRepository.save(command);
    }

    /**
     * Suspended Substrate actors send no heartbeats, so the heartbeat lease cannot tell whether they exist;
     * Substrate itself is the authority. Only an operator-set OFFLINE status excludes them.
     */
    private Optional<AgentRuntimeInstance> substrateRuntime(Long agentId) {
        return runtimeRepository.findByAgentId(agentId).stream()
                .filter(runtime -> SubstrateActorRef.isSubstrate(runtime.getProtocol()) && runtime.getStatus() != RuntimeStatus.OFFLINE)
                .min(Comparator.comparing(AgentRuntimeInstance::getLoadScore));
    }

    private void writeAudit(AgentControlCommand command, String actor, String action) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActor(actor);
        auditLog.setAction(action);
        auditLog.setResourceType("AGENT");
        auditLog.setResourceId(String.valueOf(command.agentId));
        auditLog.setBeforeJson(null);
        auditLog.setAfterJson("{\"commandKey\":\"" + command.commandKey + "\",\"commandType\":\"" + command.commandType + "\",\"status\":\"" + command.status + "\"}");
        auditLog.setResult(command.status == ControlCommandStatus.FAILED ? "FAILED" : "SUCCESS");
        auditLog.setTraceId(TraceContext.traceId());
        auditLog.setCreatedAt(Instant.now());
        auditLogRepository.save(auditLog);
    }
}
