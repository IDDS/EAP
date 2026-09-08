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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
            "SET_RATE_LIMIT"
    );

    private final AgentControlCommandRepository commandRepository;
    private final AgentRepository agentRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final AuditLogRepository auditLogRepository;
    private final ControlDispatchProperties dispatchProperties;
    private final AgentControlAdapter httpAdapter;
    private final RuntimeLeaseProperties leaseProperties;

    public AgentControlService(
            AgentControlCommandRepository commandRepository,
            AgentRepository agentRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            AuditLogRepository auditLogRepository,
            ControlDispatchProperties dispatchProperties,
            AgentControlAdapter httpAdapter,
            RuntimeLeaseProperties leaseProperties
    ) {
        this.commandRepository = commandRepository;
        this.agentRepository = agentRepository;
        this.runtimeRepository = runtimeRepository;
        this.auditLogRepository = auditLogRepository;
        this.dispatchProperties = dispatchProperties;
        this.httpAdapter = httpAdapter;
        this.leaseProperties = leaseProperties;
    }

    @Transactional(readOnly = true)
    public List<AgentControlDtos.CommandResponse> recent() {
        return commandRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(AgentControlDtos::from)
                .toList();
    }

    @Transactional
    public AgentControlDtos.CommandResponse issue(AgentControlDtos.CommandRequest request) {
        String commandType = request.commandType().toUpperCase(Locale.ROOT);
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
        command.status = ControlCommandStatus.QUEUED;
        command.attemptCount = 0;
        command.resultMessage = dispatchProperties.isEnabled()
                ? "控制命令已写入 outbox，准备投递到在线 Agent Runtime。"
                : "控制命令已写入 outbox；HTTP Runtime 适配器未启用。";
        command = commandRepository.save(command);
        if (dispatchProperties.isEnabled()) dispatch(command);
        writeAudit(command);
        return AgentControlDtos.from(command);
    }

    @Transactional
    public AgentControlDtos.CommandResponse retry(String commandKey) {
        AgentControlCommand command = commandRepository.findByCommandKey(commandKey)
                .orElseThrow(() -> new BusinessException("控制命令不存在"));
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
        writeAudit(command);
        return AgentControlDtos.from(command);
    }

    private void dispatch(AgentControlCommand command) {
        command.attemptCount = (command.attemptCount == null ? 0 : command.attemptCount) + 1;
        AgentRuntimeInstance runtime = runtimeRepository
                .findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(command.agentId, RuntimeStatus.ONLINE, leaseProperties.cutoff())
                .orElse(null);
        if (runtime == null) {
            command.status = ControlCommandStatus.FAILED;
            command.dispatchMode = "HTTP_RUNTIME";
            command.resultMessage = "没有可用的 ONLINE Agent Runtime。";
            commandRepository.save(command);
            return;
        }
        command.runtimeInstanceKey = runtime.getInstanceKey();
        command.dispatchMode = "HTTP_RUNTIME";
        command.dispatchedAt = Instant.now();
        try {
            AgentControlAdapter.DispatchResult result = httpAdapter.dispatch(command, runtime);
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

    private void writeAudit(AgentControlCommand command) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActor(command.issuedBy);
        auditLog.setAction("ISSUE_CONTROL_COMMAND");
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
