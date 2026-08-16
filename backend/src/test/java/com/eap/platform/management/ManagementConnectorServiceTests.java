package com.eap.platform.management;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagementConnectorServiceTests {
    @Mock ManagementValueConnectorRepository connectorRepository;
    @Mock ManagementValueEventRepository valueRepository;
    @Mock AgentRepository agentRepository;
    @Mock DiscoveryEndpointRepository endpointRepository;
    @Mock ManagementService managementService;
    @Mock ManagementConnectorClient client;

    private ManagementConnectorService service;

    @BeforeEach
    void setUp() {
        service = new ManagementConnectorService(
                connectorRepository, valueRepository, agentRepository, endpointRepository,
                managementService, client
        );
    }

    @Test
    void synchronizesMappedBusinessRecordsAndSkipsExistingEvidence() throws Exception {
        ManagementValueConnector connector = connector();
        when(connectorRepository.findById(5L)).thenReturn(Optional.of(connector));
        when(connectorRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.fetch(connector)).thenReturn(new ObjectMapper().readTree("""
                {"data":{"records":[
                  {"id":"deal-1","closedAt":"2026-08-16T01:00:00Z","benefit":"120.50","description":"智能体辅助成交"},
                  {"id":"deal-2","closedAt":"2026-08-16T02:00:00Z","benefit":80,"description":"续费挽回"}
                ]}}
                """));
        when(valueRepository.findBySourceSystemAndSourceRecordKey("CRM", "deal-1"))
                .thenReturn(Optional.of(new ManagementValueEvent()));
        when(valueRepository.findBySourceSystemAndSourceRecordKey("CRM", "deal-2"))
                .thenReturn(Optional.empty());

        ManagementConnectorDtos.SyncResult result = service.sync(5L);

        assertThat(result.success()).isTrue();
        assertThat(result.fetchedRecords()).isEqualTo(2);
        assertThat(result.importedRecords()).isEqualTo(1);
        assertThat(result.skippedRecords()).isEqualTo(1);
        assertThat(connector.status).isEqualTo("HEALTHY");
        assertThat(connector.lastSuccessAt).isNotNull();
        ArgumentCaptor<ManagementDtos.ValueEventRequest> request = ArgumentCaptor.forClass(ManagementDtos.ValueEventRequest.class);
        verify(managementService).recordValue(request.capture());
        assertThat(request.getValue().sourceSystem()).isEqualTo("CRM");
        assertThat(request.getValue().sourceRecordKey()).isEqualTo("deal-2");
        assertThat(request.getValue().benefitMoney()).isEqualByComparingTo("80");
        assertThat(request.getValue().agentId()).isEqualTo(4L);
        assertThat(request.getValue().observedAt()).isEqualTo(Instant.parse("2026-08-16T02:00:00Z"));
    }

    private ManagementValueConnector connector() {
        ManagementValueConnector connector = new ManagementValueConnector();
        connector.id = 5L;
        connector.connectorKey = "crm-deals";
        connector.name = "CRM 成交价值";
        connector.systemType = "CRM";
        connector.sourceSystem = "CRM";
        connector.endpointUrl = "https://crm.example.com/api/deals";
        connector.enabled = true;
        connector.syncIntervalMinutes = 60;
        connector.authType = "NONE";
        connector.recordsPath = "data.records";
        connector.recordKeyPath = "id";
        connector.observedAtPath = "closedAt";
        connector.fixedValueType = "REVENUE";
        connector.descriptionPath = "description";
        connector.benefitMoneyPath = "benefit";
        connector.fixedCurrency = "USD";
        connector.improvementDirection = "HIGHER_IS_BETTER";
        connector.defaultAgentId = 4L;
        connector.validatedBy = "CRM 自动同步";
        connector.confidence = new BigDecimal("0.9500");
        connector.maxRecords = 500;
        connector.status = "NEVER_SYNCED";
        connector.lastImportedCount = 0;
        connector.lastSkippedCount = 0;
        return connector;
    }
}
