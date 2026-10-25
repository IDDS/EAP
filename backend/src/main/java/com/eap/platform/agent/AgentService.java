package com.eap.platform.agent;

import com.eap.platform.common.BusinessException;
import com.eap.platform.config.SecurityProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentService {
    private final AgentRepository agentRepository;
    private final SecurityProperties securityProperties;

    public AgentService(AgentRepository agentRepository, SecurityProperties securityProperties) {
        this.agentRepository = agentRepository;
        this.securityProperties = securityProperties;
    }

    @Transactional(readOnly = true)
    public List<AgentDtos.AgentResponse> list() {
        return agentRepository.findAll().stream()
                .filter(this::visible)
                .map(AgentDtos::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentDtos.AgentResponse get(Long id) {
        return AgentDtos.from(findAgent(id));
    }

    @Transactional(readOnly = true)
    public boolean isVisible(Long id) {
        return agentRepository.findById(id).filter(this::visible).isPresent();
    }

    @Transactional
    public AgentDtos.AgentResponse create(AgentDtos.AgentRequest request) {
        if (agentRepository.existsByCode(request.code())) {
            throw new BusinessException("智能体编码已存在");
        }
        Agent agent = new Agent();
        applyRequest(agent, request);
        return AgentDtos.from(agentRepository.save(agent));
    }

    @Transactional
    public AgentDtos.AgentResponse update(Long id, AgentDtos.AgentRequest request) {
        Agent agent = findAgent(id);
        if (!agent.getCode().equals(request.code()) && agentRepository.existsByCode(request.code())) {
            throw new BusinessException("智能体编码已存在");
        }
        applyRequest(agent, request);
        return AgentDtos.from(agent);
    }

    @Transactional
    public AgentDtos.AgentResponse updateStatus(Long id, AgentLifecycleStatus status) {
        Agent agent = findAgent(id);
        agent.setLifecycleStatus(status);
        return AgentDtos.from(agent);
    }

    private Agent findAgent(Long id) {
        return agentRepository.findById(id).filter(this::visible)
                .orElseThrow(() -> new BusinessException("智能体不存在"));
    }

    private boolean visible(Agent agent) {
        return securityProperties.isDevelopmentMode() || agent.getRecordOrigin() != AgentRecordOrigin.DEMO;
    }

    private void applyRequest(Agent agent, AgentDtos.AgentRequest request) {
        agent.setCode(request.code());
        agent.setName(request.name());
        agent.setDescription(request.description());
        agent.setOwnerDepartment(request.ownerDepartment());
        agent.setOwnerUser(request.ownerUser());
        agent.setCategory(request.category());
        agent.setRiskLevel(request.riskLevel());
    }
}
