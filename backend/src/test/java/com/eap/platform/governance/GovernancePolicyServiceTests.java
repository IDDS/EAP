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

    @Mock
    private org.springframework.jdbc.core.JdbcTemplate db;

    private GovernancePolicyService service;

    @BeforeEach
    void setUp() {
        service = new GovernancePolicyService(repository, new ObjectMapper(), db);
        org.mockito.Mockito.lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void canonicalizesValidRuleBeforeSaving() {
        service.create(request("{ \"sensitivities\" : [\"SECRET\"], \"action\" : \"BLOCK\" }"));

        ArgumentCaptor<GovernancePolicy> captor = ArgumentCaptor.forClass(GovernancePolicy.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRuleJson())
                .isEqualTo("{\"action\":\"BLOCK\",\"sensitivities\":[\"SECRET\"]}");
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

    @Test
    void historyAndResponseUseTheManagedEntityReturnedByTheRepository() {
        org.mockito.Mockito.when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            GovernancePolicy incoming = invocation.getArgument(0), saved = new GovernancePolicy();
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 17L);
            saved.setName(incoming.getName()); saved.setPolicyType(incoming.getPolicyType());
            saved.setScopeType(incoming.getScopeType()); saved.setScopeValue(incoming.getScopeValue());
            saved.setRuleJson(incoming.getRuleJson()); saved.setEnforcementMode(incoming.getEnforcementMode());
            saved.setEnabled(incoming.isEnabled()); return saved;
        });
        assertThat(service.create(request("{}")).id()).isEqualTo(17L);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(db).update(org.mockito.ArgumentMatchers.anyString(), arguments.capture());
        assertThat(arguments.getValue()[0]).isEqualTo(17L);
    }

    private GovernancePolicyDtos.PolicyRequest request(String ruleJson) {
        return new GovernancePolicyDtos.PolicyRequest(
                "公司保密信息阻断", PolicyType.PRIVACY, "GLOBAL", "",
                ruleJson, EnforcementMode.BLOCK, true
        );
    }
}
