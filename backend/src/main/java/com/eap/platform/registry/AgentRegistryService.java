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
    private final AgentRepository agentRepository;
    private final AgentCapabilityRepository capabilityRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;

    public AgentRegistryService(AgentRepository agentRepository, AgentCapabilityRepository capabilityRepository, AgentRuntimeInstanceRepository runtimeRepository) {
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
        return runtimeRepository.findByAgentId(agentId).stream().map(AgentRegistryDtos::from).toList();
    }

    @Transactional
    public AgentRegistryDtos.RuntimeInstanceResponse registerRuntime(Long agentId, AgentRegistryDtos.RuntimeInstanceRequest request) {
        ensureAgent(agentId);
        AgentRuntimeInstance instance = runtimeRepository.findByInstanceKey(request.instanceKey()).orElseGet(AgentRuntimeInstance::new);
        instance.agentId = agentId;
        instance.instanceKey = request.instanceKey();
        instance.endpoint = request.endpoint();
        instance.protocol = request.protocol();
        instance.status = RuntimeStatus.ONLINE;
        instance.lastHeartbeatAt = Instant.now();
        return AgentRegistryDtos.from(runtimeRepository.save(instance));
    }

    @Transactional
    public AgentRegistryDtos.RuntimeInstanceResponse heartbeat(String instanceKey, AgentRegistryDtos.HeartbeatRequest request) {
        AgentRuntimeInstance instance = runtimeRepository.findByInstanceKey(instanceKey)
                .orElseThrow(() -> new BusinessException("运行实例不存在"));
        instance.status = request.status();
        instance.loadScore = request.loadScore();
        instance.lastHeartbeatAt = Instant.now();
        return AgentRegistryDtos.from(instance);
    }

    @Transactional(readOnly = true)
    public AgentRegistryDtos.DiscoveryResponse discover(AgentRegistryDtos.DiscoveryRequest request) {
        RiskLevel maxRisk = request.maxRiskLevel() == null ? RiskLevel.CRITICAL : request.maxRiskLevel();
        List<AgentRegistryDtos.DiscoveryCandidate> candidates = capabilityRepository.findByCapability(request.capability()).stream()
                .flatMap(capability -> runtimeRepository.findByAgentId(capability.agentId).stream()
                        .map(instance -> toCandidate(request, maxRisk, capability, instance)))
                .flatMap(List::stream)
                .sorted(Comparator.comparing(AgentRegistryDtos.DiscoveryCandidate::score).reversed())
                .toList();
        return new AgentRegistryDtos.DiscoveryResponse(candidates);
    }

    private List<AgentRegistryDtos.DiscoveryCandidate> toCandidate(AgentRegistryDtos.DiscoveryRequest request, RiskLevel maxRisk, AgentCapability capability, AgentRuntimeInstance instance) {
        Agent agent = agentRepository.findById(capability.agentId).orElse(null);
        if (agent == null || agent.getLifecycleStatus() != AgentLifecycleStatus.ACTIVE || instance.status != RuntimeStatus.ONLINE) {
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
        List<String> constraints = List.of("runtime-online", "safety-evaluation-required", "delegation-audit-required");
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

