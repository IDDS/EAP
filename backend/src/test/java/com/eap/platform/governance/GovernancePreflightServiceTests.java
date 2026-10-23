package com.eap.platform.governance;

import com.eap.platform.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernancePreflightServiceTests {
    @Mock
    private GovernancePreflightDecisionRepository decisionRepository;
    @Mock
    private GovernancePolicyRepository policyRepository;
    @Mock
    private GovernanceDataService dataService;
    @Mock
    private GovernanceDataEventRepository dataEventRepository;
    @Mock
    private AuditLogRepository auditLogRepository;

    private GovernanceDataProperties properties;
    private GovernancePreflightService service;

    @BeforeEach
    void setUp() {
        properties = new GovernanceDataProperties();
        service = new GovernancePreflightService(
                decisionRepository, policyRepository, dataService, dataEventRepository, properties,
                auditLogRepository, new ObjectMapper()
        );
        org.mockito.Mockito.lenient().when(decisionRepository.findByDecisionKey(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(dataService.record(any())).thenReturn(dataEvent());
        org.mockito.Mockito.lenient().when(decisionRepository.save(any())).thenAnswer(invocation -> {
            GovernancePreflightDecision decision = invocation.getArgument(0);
            decision.id = 21L;
            return decision;
        });
    }

    @Test
    void failsClosedForCompanySecretWithoutPolicy() {
        when(policyRepository.findByEnabledTrue()).thenReturn(List.of());

        GovernanceDataDtos.PreflightResponse response = service.evaluate(request(
                "decision-secret", GovernanceDataSensitivity.SECRET, true, List.of("SOURCE_CODE")
        ));

        assertThat(response.action()).isEqualTo(GovernanceDataAction.BLOCK);
        assertThat(response.gatewayDirective()).isEqualTo(GovernanceGatewayDirective.BLOCK);
        assertThat(response.requestAllowed()).isFalse();
        assertThat(response.reasonCodes()).contains("DEFAULT_SECRET_FAIL_CLOSED");
        verify(auditLogRepository).save(any());
    }

    @Test
    void matchesMaskPolicyAndRequiresRedactionRecheck() {
        GovernancePolicy policy = policy(
                7L, "个人信息脱敏", PolicyType.PRIVACY,
                "{\"piiTypes\":[\"PHONE\",\"ID_CARD\"],\"action\":\"REDACT\"}",
                EnforcementMode.BLOCK
        );
        when(policyRepository.findByEnabledTrue()).thenReturn(List.of(policy));

        GovernanceDataDtos.PreflightResponse response = service.evaluate(request(
                "decision-pii", GovernanceDataSensitivity.CONFIDENTIAL, false, List.of("PHONE")
        ));

        assertThat(response.action()).isEqualTo(GovernanceDataAction.REDACT);
        assertThat(response.gatewayDirective()).isEqualTo(GovernanceGatewayDirective.REDACT_AND_RECHECK);
        assertThat(response.requestAllowed()).isFalse();
        assertThat(response.matchedPolicies()).singleElement().extracting(GovernanceDataDtos.MatchedPolicy::id).isEqualTo(7L);
    }

    @Test
    void allowsPublicDataWhenNoRestrictivePolicyMatches() {
        when(policyRepository.findByEnabledTrue()).thenReturn(List.of());

        GovernanceDataDtos.PreflightResponse response = service.evaluate(request(
                "decision-public", GovernanceDataSensitivity.PUBLIC, false, List.of()
        ));

        assertThat(response.action()).isEqualTo(GovernanceDataAction.ALLOW);
        assertThat(response.gatewayDirective()).isEqualTo(GovernanceGatewayDirective.PROCEED);
        assertThat(response.requestAllowed()).isTrue();
    }

    @Test
    void approvalTransitionsToApprovedAndCanBeReusedOnlyWithSameFingerprint() {
        GovernancePreflightDecision decision = new GovernancePreflightDecision();
        decision.decisionKey = "decision-approval";
        decision.dataEventId = 11L;
        decision.action = GovernanceDataAction.APPROVAL_REQUIRED;
        decision.gatewayDirective = GovernanceGatewayDirective.REQUEST_APPROVAL;
        decision.requestAllowed = false;
        decision.approvalStatus = GovernanceApprovalStatus.PENDING;
        decision.provider = "OpenAI";
        decision.modelName = "gpt-test";
        decision.requestFingerprint = "fingerprint-1";
        decision.decidedAt = Instant.now();
        decision.policyContextDigest = GovernanceApprovalContext.digest(new ObjectMapper(), properties, List.of());
        when(decisionRepository.findByDecisionKey("decision-approval")).thenReturn(Optional.of(decision));
        when(dataEventRepository.findById(11L)).thenReturn(Optional.of(dataEventEntity()));
        GovernanceDataDtos.PreflightResponse approved = service.approve(
                "decision-approval", new GovernanceDataDtos.ApprovalRequest("alice", "业务例外已核验")
        );

        assertThat(approved.approvalStatus()).isEqualTo(GovernanceApprovalStatus.APPROVED);
        assertThat(approved.gatewayDirective()).isEqualTo(GovernanceGatewayDirective.PROCEED_WITH_WARNING);
        assertThat(approved.requestAllowed()).isTrue();
        assertThat(service.reusableDecision("decision-approval", null, "endpoint-1", "OpenAI", "gpt-test", "fingerprint-1").decisionKey())
                .isEqualTo("decision-approval");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.reusableDecision(
                "decision-approval", null, "endpoint-1", "OpenAI", "gpt-test", "fingerprint-2"
        )).isInstanceOf(com.eap.platform.common.BusinessException.class).hasMessageContaining("请求内容不一致");
    }

    private GovernanceDataDtos.PreflightRequest request(
            String decisionKey,
            GovernanceDataSensitivity sensitivity,
            boolean companySecret,
            List<String> detectedTypes
    ) {
        return new GovernanceDataDtos.PreflightRequest(
                decisionKey, "event-" + decisionKey, null, "endpoint-1", null, "trace-1",
                GovernanceDataDirection.INPUT_TO_MODEL, "CUSTOMER_DATA", sensitivity,
                sensitivity != GovernanceDataSensitivity.PUBLIC, companySecret, 120, 1024,
                "demo-dlp", "OpenAI", "gpt-test", detectedTypes, null, Instant.now()
        );
    }

    private GovernancePolicy policy(Long id, String name, PolicyType type, String rule, EnforcementMode mode) {
        GovernancePolicy policy = new GovernancePolicy();
        ReflectionTestUtils.setField(policy, "id", id);
        policy.setName(name);
        policy.setPolicyType(type);
        policy.setScopeType("GLOBAL");
        policy.setRuleJson(rule);
        policy.setEnforcementMode(mode);
        policy.setEnabled(true);
        return policy;
    }

    private GovernanceDataDtos.DataEventResponse dataEvent() {
        return new GovernanceDataDtos.DataEventResponse(
                11L, "event", null, "endpoint-1", null, "trace-1",
                GovernanceDataDirection.INPUT_TO_MODEL, "CUSTOMER_DATA", GovernanceDataSensitivity.INTERNAL,
                GovernanceDataAction.WARN, true, false, 120, 1024, null, null, Instant.now(), Instant.now()
        );
    }

    private GovernanceDataEvent dataEventEntity() {
        GovernanceDataEvent event = new GovernanceDataEvent();
        event.id = 11L;
        event.agentId = null;
        event.endpointKey = "endpoint-1";
        return event;
    }
}
