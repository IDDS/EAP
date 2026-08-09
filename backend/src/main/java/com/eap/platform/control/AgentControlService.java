package com.eap.platform.control;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
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
    private final AuditLogRepository auditLogRepository;

    public AgentControlService(AgentControlCommandRepository commandRepository, AgentRepository agentRepository, AuditLogRepository auditLogRepository) {
        this.commandRepository = commandRepository;
        this.agentRepository = agentRepository;
        this.auditLogRepository = auditLogRepository;
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
        command.status = ControlCommandStatus.DISPATCHED;
        command.dispatchedAt = Instant.now();
        command.resultMessage = "控制命令已写入 outbox，并以模拟派发模式送达 Agent Runtime。";
        command = commandRepository.save(command);
        writeAudit(command);
        return AgentControlDtos.from(command);
    }

    private void writeAudit(AgentControlCommand command) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActor(command.issuedBy);
        auditLog.setAction("ISSUE_CONTROL_COMMAND");
        auditLog.setResourceType("AGENT");
        auditLog.setResourceId(String.valueOf(command.agentId));
        auditLog.setBeforeJson(null);
        auditLog.setAfterJson("{\"commandKey\":\"" + command.commandKey + "\",\"commandType\":\"" + command.commandType + "\",\"status\":\"" + command.status + "\"}");
        auditLog.setResult("SUCCESS");
        auditLog.setTraceId(TraceContext.traceId());
        auditLog.setCreatedAt(Instant.now());
        auditLogRepository.save(auditLog);
    }
}
