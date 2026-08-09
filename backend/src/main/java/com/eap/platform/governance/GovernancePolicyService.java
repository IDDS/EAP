package com.eap.platform.governance;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GovernancePolicyService {
    private final GovernancePolicyRepository repository;
    private final ObjectMapper objectMapper;

    public GovernancePolicyService(GovernancePolicyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<GovernancePolicyDtos.PolicyResponse> list() {
        return repository.findAll().stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public GovernancePolicyDtos.PolicyResponse create(GovernancePolicyDtos.PolicyRequest request) {
        GovernancePolicy policy = new GovernancePolicy();
        apply(policy, request);
        return response(repository.save(policy));
    }

    @Transactional
    public GovernancePolicyDtos.PolicyResponse update(Long id, GovernancePolicyDtos.PolicyRequest request) {
        GovernancePolicy policy = repository.findById(id)
                .orElseThrow(() -> new BusinessException("治理策略不存在"));
        apply(policy, request);
        return response(repository.saveAndFlush(policy));
    }

    private void apply(GovernancePolicy policy, GovernancePolicyDtos.PolicyRequest request) {
        policy.setName(request.name());
        policy.setPolicyType(request.policyType());
        policy.setScopeType(request.scopeType());
        policy.setScopeValue(request.scopeValue());
        policy.setRuleJson(canonicalRuleJson(request.ruleJson()));
        policy.setEnforcementMode(request.enforcementMode());
        policy.setEnabled(request.enabled());
    }

    private String canonicalRuleJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.asText());
            if (node == null || !node.isObject()) {
                throw new BusinessException("治理策略规则必须是 JSON 对象");
            }
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("治理策略规则不是有效 JSON");
        }
    }

    private GovernancePolicyDtos.PolicyResponse response(GovernancePolicy policy) {
        return new GovernancePolicyDtos.PolicyResponse(
                policy.getId(), policy.getName(), policy.getPolicyType(), policy.getScopeType(), policy.getScopeValue(),
                normalizedRuleJson(policy.getRuleJson()), policy.getEnforcementMode(), policy.isEnabled(),
                policy.getCreatedAt(), policy.getUpdatedAt()
        );
    }

    private String normalizedRuleJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.asText());
            return node == null ? "{}" : objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }
}
