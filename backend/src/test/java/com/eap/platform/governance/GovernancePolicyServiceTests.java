package com.eap.platform.governance;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GovernancePolicyServiceTests {
    @Mock
    private GovernancePolicyRepository repository;

    private GovernancePolicyService service;

    @BeforeEach
    void setUp() {
        service = new GovernancePolicyService(repository, new ObjectMapper());
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void canonicalizesValidRuleBeforeSaving() {
        service.create(request("{ \"sensitivities\" : [\"SECRET\"], \"action\" : \"BLOCK\" }"));

        ArgumentCaptor<GovernancePolicy> captor = ArgumentCaptor.forClass(GovernancePolicy.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRuleJson())
                .isEqualTo("{\"sensitivities\":[\"SECRET\"],\"action\":\"BLOCK\"}");
    }

    @Test
    void rejectsInvalidOrNonObjectRules() {
        assertThatThrownBy(() -> service.create(request("not-json")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效 JSON");
        assertThatThrownBy(() -> service.create(request("[\"SECRET\"]")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 对象");
    }

    private GovernancePolicyDtos.PolicyRequest request(String ruleJson) {
        return new GovernancePolicyDtos.PolicyRequest(
                "公司保密信息阻断", PolicyType.PRIVACY, "GLOBAL", "",
                ruleJson, EnforcementMode.BLOCK, true
        );
    }
}
