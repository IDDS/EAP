package com.eap.platform.registry;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class AgentRegistryService {
    private final RuntimeLeaseProperties leaseProperties;
    private final AgentRepository agentRepository;
    private final AgentCapabilityRepository capabilityRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;

    public AgentRegistryService(AgentRepository agentRepository, AgentCapabilityRepository capabilityRepository, AgentRuntimeInstanceRepository runtimeRepository, RuntimeLeaseProperties leaseProperties) {
        this.leaseProperties = leaseProperties;
        this.agentRepository = agentRepository;
        this.capabilityRepository = capabilityRepository;
        this.runtimeRepository = runtimeRepository;
    }

    @Transactional(readOnly = true)
    public List<AgentRegistryDtos.CapabilityResponse> listCapabilities(Long agentId) {
        return capabilityRepository.findByAgentId(agentId).stream().map(AgentRegistryDtos::from).toList();
    }

    @Transactional
    public AgentRegistryDtos.CapabilityResponse registerCapability(Long agentId, AgentRegistryDtos.CapabilityRequest request) {
        ensureAgent(agentId);
        AgentCapability capability = new AgentCapability();
        capability.agentId = agentId;
        capability.capability = request.capability();
        capability.name = request.name();
        capability.description = request.description();
        return AgentRegistryDtos.from(capabilityRepository.save(capability));
    }

    @Transactional(readOnly = true)
    public List<AgentRegistryDtos.RuntimeInstanceResponse> listRuntimeInstances(Long agentId) {
        Instant cutoff = leaseProperties.cutoff();
        return runtimeRepository.findByAgentId(agentId).stream()
                .map(instance -> AgentRegistryDtos.from(instance, leaseProperties.effectiveStatus(instance, cutoff))).toList();
    }

    @Transactional
    public AgentRegistryDtos.RuntimeInstanceResponse registerRuntime(Long agentId, AgentRegistryDtos.RuntimeInstanceRequest request) {
        ensureAgent(agentId);
        if ("SUBSTRATE_OBSERVED".equalsIgnoreCase(request.protocol())) throw new BusinessException("外部实例必须通过运行环境发现复核纳管");
        if ("SUBSTRATE_TASK".equalsIgnoreCase(request.protocol())) throw new BusinessException("任务实例只接受原任务创建回执绑定");
        AgentRuntimeInstance instance = runtimeRepository.findByInstanceKey(request.instanceKey()).orElseGet(AgentRuntimeInstance::new);
        if (instance.hasBoundIdentity() || instance.agentId != null && !agentId.equals(instance.agentId))
            throw new com.eap.platform.common.ConflictException("实例已有归属，不允许覆盖绑定");
        instance.agentId = agentId;
        instance.instanceKey = request.instanceKey();
        instance.endpoint = request.endpoint();
        instance.protocol = request.protocol();
        instance.dispatchAuthenticated = Boolean.TRUE.equals(request.dispatchAuthenticated())
                && "A2A".equalsIgnoreCase(request.protocol());
        instance.status = RuntimeStatus.ONLINE;
        instance.lastHeartbeatAt = Instant.now();
        return AgentRegistryDtos.from(runtimeRepository.save(instance));
    }

    @Transactional
    public AgentRegistryDtos.RuntimeInstanceResponse heartbeat(String instanceKey, AgentRegistryDtos.HeartbeatRequest request) {
        AgentRuntimeInstance instance = runtimeRepository.findByInstanceKey(instanceKey)
                .orElseThrow(() -> new BusinessException("运行实例不存在"));
        if (instance.hasBoundIdentity()) throw new BusinessException("外部纳管实例不接受普通心跳覆盖，请使用已认证的接入验证");
        instance.status = request.status();
        instance.loadScore = request.loadScore();
        instance.lastHeartbeatAt = Instant.now();
        return AgentRegistryDtos.from(instance);
    }

    /** Trusted platform factory only: no console registration accepts a claimed native UID. */
    @Transactional
    public AgentRuntimeInstance bindPlatformRuntime(Long agentId, String key, String uid, String template) {
        ensureAgent(agentId);
        var instance = runtimeRepository.lockForScopedTools(key).orElseGet(AgentRuntimeInstance::new);
        if (instance.agentId != null && (!agentId.equals(instance.agentId) || instance.status == RuntimeStatus.OFFLINE
                || !uid.equals(instance.platformActorUid) || !template.equals(instance.platformActorTemplate)
                || instance.importedActorUid != null))
            throw new com.eap.platform.common.ConflictException("原实例绑定已变化，不能覆盖或恢复");
        instance.agentId=agentId;instance.instanceKey=key;instance.endpoint="substrate://"+key;
        instance.protocol="SUBSTRATE";instance.platformActorUid=uid;instance.platformActorTemplate=template;
        instance.dispatchAuthenticated=false;instance.status=RuntimeStatus.ONLINE;
        return runtimeRepository.saveAndFlush(instance);
    }

    @Transactional(readOnly = true)
    public AgentRegistryDtos.DiscoveryResponse discover(AgentRegistryDtos.DiscoveryRequest request) {
        Instant cutoff = leaseProperties.cutoff();
        RiskLevel maxRisk = request.maxRiskLevel() == null ? RiskLevel.CRITICAL : request.maxRiskLevel();
        List<AgentRegistryDtos.DiscoveryCandidate> candidates = capabilityRepository.findByCapability(request.capability()).stream()
                .flatMap(capability -> runtimeRepository.findByAgentId(capability.agentId).stream()
                        .map(instance -> toCandidate(request, maxRisk, capability, instance, cutoff)))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(AgentRegistryDtos.DiscoveryCandidate::score).reversed())
                .toList();
        return new AgentRegistryDtos.DiscoveryResponse(candidates);
    }

    private List<AgentRegistryDtos.DiscoveryCandidate> toCandidate(AgentRegistryDtos.DiscoveryRequest request, RiskLevel maxRisk, AgentCapability capability, AgentRuntimeInstance instance, Instant cutoff) {
        Agent agent = agentRepository.findById(capability.agentId).orElse(null);
        if (agent == null || agent.getLifecycleStatus() != AgentLifecycleStatus.ACTIVE
                || leaseProperties.effectiveStatus(instance, cutoff) != RuntimeStatus.ONLINE
                || !"A2A".equalsIgnoreCase(instance.protocol)
                || !Boolean.TRUE.equals(instance.dispatchAuthenticated)) {
            return List.of();
        }
        if (agent.getRiskLevel().ordinal() > maxRisk.ordinal()) {
            return List.of();
        }
        if (request.department() != null && !request.department().isBlank() && !agent.getOwnerDepartment().equals(request.department())) {
            return List.of();
        }
        if (request.protocol() != null && !request.protocol().isBlank() && !instance.protocol.equalsIgnoreCase(request.protocol())) {
            return List.of();
        }
        int score = 100 - instance.loadScore - agent.getRiskLevel().ordinal() * 8;
        List<String> constraints = List.of("runtime-online", "a2a-authenticated", "safety-evaluation-required", "delegation-audit-required");
        return List.of(new AgentRegistryDtos.DiscoveryCandidate(
                agent.getId(),
                agent.getCode(),
                agent.getName(),
                agent.getOwnerDepartment(),
                agent.getLifecycleStatus(),
                agent.getRiskLevel(),
                capability.capability,
                instance.endpoint,
                instance.protocol,
                instance.status,
                instance.loadScore,
                score,
                constraints
        ));
    }

    private void ensureAgent(Long agentId) {
        if (!agentRepository.existsById(agentId)) {
            throw new BusinessException("智能体不存在");
        }
    }
}
