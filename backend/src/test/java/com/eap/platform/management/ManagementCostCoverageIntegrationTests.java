package com.eap.platform.management;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Transactional
class ManagementCostCoverageIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired ManagementService management;
    @Autowired EntityManager entityManager;

    @Test
    void incompleteCostsAcrossCurrenciesBlockReturnsOnlyForAffectedSubjects() {
        db.update("update model_invocation_events set observed_at = timestamp '2000-01-01 00:00:00'");
        db.update("update management_value_events set observed_at = timestamp '2000-01-01 00:00:00'");
        addModel("coverage-known-1", 1, "USD", "2", "REPORTED");
        addModel("coverage-known-2", 2, "USD", "2", "REPORTED");
        addModel("coverage-unconfirmed", 1, "CNY", "0", "MISSING");
        addValue("coverage-value-1", 1);
        addValue("coverage-value-2", 2);

        var missing = management.summary(24, "USD");
        assertThat(missing.totalCost()).isEqualByComparingTo("4");
        assertThat(missing.validatedBusinessValue()).isEqualByComparingTo("20");
        assertThat(missing.netBenefit()).isNull();
        assertThat(missing.roi()).isNull();
        assertThat(missing.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().roi()).isNull();
        assertThat(missing.agentUsage().stream().filter(row -> Long.valueOf(2).equals(row.agentId())).findFirst().orElseThrow().roi()).isEqualByComparingTo("4");
        db.update("update model_invocation_events set cost_provenance = 'ESTIMATED' where event_key = 'coverage-unconfirmed'");
        entityManager.clear();
        assertThat(management.summary(24, "USD").roi()).isNull();
        db.update("update model_invocation_events set cost_provenance = null where event_key = 'coverage-unconfirmed'");
        entityManager.clear();
        assertThat(management.summary(24, "USD").roi()).isNull();
        db.update("update model_invocation_events set cost_provenance = 'REPORTED' where event_key = 'coverage-unconfirmed'");
        entityManager.clear();
        var reported = management.summary(24, "USD");
        assertThat(reported.netBenefit()).isEqualByComparingTo("16");
        assertThat(reported.roi()).isEqualByComparingTo("4");

        addValue("coverage-value-no-calls", 3);
        entityManager.clear();
        var partial = management.summary(24, "USD");
        assertThat(partial.validatedBusinessValue()).isEqualByComparingTo("30");
        assertThat(partial.totalCost()).isEqualByComparingTo("4");
        assertThat(partial.netBenefit()).isNull();
        assertThat(partial.roi()).isNull();
        assertThat(partial.agentUsage().stream().filter(row -> Long.valueOf(2).equals(row.agentId())).findFirst().orElseThrow().roi()).isEqualByComparingTo("4");
        assertThat(partial.agentUsage().stream().filter(row -> Long.valueOf(3).equals(row.agentId())).findFirst().orElseThrow().netBenefit()).isNull();

        addModel("coverage-known-3", 3, "USD", "2", "REPORTED");
        entityManager.clear();
        var complete = management.summary(24, "USD");
        assertThat(complete.netBenefit()).isEqualByComparingTo("24");
        assertThat(complete.roi()).isEqualByComparingTo("4");
    }

    @Test
    void noSelectedCurrencyCallsDoesNotTreatMissingEvidenceAsZeroCost() {
        db.update("update model_invocation_events set observed_at = timestamp '2000-01-01 00:00:00'");
        db.update("update management_value_events set observed_at = timestamp '2000-01-01 00:00:00'");
        addValue("coverage-no-observations", 1);

        var noEvents = management.summary(24, "USD");
        assertThat(noEvents.validatedBusinessValue()).isEqualByComparingTo("10");
        assertThat(noEvents.totalCost()).isEqualByComparingTo("0");
        assertThat(noEvents.netBenefit()).isNull();
        assertThat(noEvents.roi()).isNull();
        assertThat(noEvents.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().netBenefit()).isNull();

        addModel("coverage-other-currency", 1, "CNY", "2", "REPORTED");
        entityManager.clear();
        var otherCurrencyOnly = management.summary(24, "USD");
        assertThat(otherCurrencyOnly.netBenefit()).isNull();
        assertThat(otherCurrencyOnly.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().netBenefit()).isNull();

        addModel("coverage-reported-zero", 1, "USD", "0", "REPORTED");
        entityManager.clear();
        var reportedZero = management.summary(24, "USD");
        assertThat(reportedZero.netBenefit()).isEqualByComparingTo("10");
        assertThat(reportedZero.roi()).isNull();
        assertThat(reportedZero.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().netBenefit()).isEqualByComparingTo("10");
    }

    @Test
    void missingLegacyAndExplicitZeroAmountsRemainDistinct() {
        db.update("update model_invocation_events set observed_at = timestamp '2000-01-01 00:00:00'");
        db.update("update management_value_events set observed_at = timestamp '2000-01-01 00:00:00'");
        addModel("value-cost", 1, "USD", "2", "REPORTED");
        var noValue = management.summary(24, "USD");
        assertThat(noValue.netBenefit()).isNull();
        assertThat(noValue.roi()).isNull();
        assertThat(noValue.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().roi()).isNull();

        addValue("legacy-amount", 1);
        db.update("update management_value_events set benefit_money=0, benefit_provenance=null where event_key='legacy-amount'");
        entityManager.clear();
        var unknown = management.summary(24, "USD");
        assertThat(unknown.valueCoverage()).isEqualTo(new ManagementDtos.ValueCoverage(0, 0, 1));
        assertThat(unknown.netBenefit()).isNull();
        assertThat(unknown.roi()).isNull();
        assertThat(unknown.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().roi()).isNull();

        db.update("update management_value_events set benefit_provenance='MISSING' where event_key='legacy-amount'");
        entityManager.clear();
        var missing = management.summary(24, "USD");
        assertThat(missing.valueCoverage()).isEqualTo(new ManagementDtos.ValueCoverage(0, 1, 0));
        assertThat(missing.roi()).isNull();

        db.update("update management_value_events set benefit_provenance='REPORTED' where event_key='legacy-amount'");
        entityManager.clear();
        var zero = management.summary(24, "USD");
        assertThat(zero.valueCoverage()).isEqualTo(new ManagementDtos.ValueCoverage(1, 0, 0));
        assertThat(zero.netBenefit()).isEqualByComparingTo("-2");
        assertThat(zero.roi()).isEqualByComparingTo("-1");

        addModel("value-cost-2", 2, "USD", "2", "REPORTED");
        addValue("value-missing-2", 2);
        db.update("update management_value_events set benefit_money=0, benefit_provenance='MISSING' where event_key='value-missing-2'");
        entityManager.clear();
        var partial = management.summary(24, "USD");
        assertThat(partial.valueCoverage()).isEqualTo(new ManagementDtos.ValueCoverage(1, 1, 0));
        assertThat(partial.roi()).isNull();
        assertThat(partial.agentUsage().stream().filter(row -> Long.valueOf(1).equals(row.agentId())).findFirst().orElseThrow().roi()).isEqualByComparingTo("-1");
        assertThat(partial.agentUsage().stream().filter(row -> Long.valueOf(2).equals(row.agentId())).findFirst().orElseThrow().roi()).isNull();
    }

    private void addModel(String key, long agent, String currency, String cost, String provenance) {
        db.update("insert into model_invocation_events(event_key,agent_id,provider,model_name,status,input_tokens,output_tokens,cached_input_tokens,reasoning_tokens,latency_ms,money_cost,currency,tool_call_count,mcp_server_count,observed_at,created_at,cost_provenance) values(?,?,'fixture','model','SUCCEEDED',1,1,0,0,1,?,?,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)", key, agent, cost, currency, provenance);
    }

    private void addValue(String key, long agent) {
        db.update("insert into management_value_events(event_key,agent_id,value_type,validated_by,benefit_money,benefit_provenance,currency,observed_at,created_at) values(?,?,'REVENUE','fixture',10,'REPORTED','USD',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", key, agent);
    }
}
