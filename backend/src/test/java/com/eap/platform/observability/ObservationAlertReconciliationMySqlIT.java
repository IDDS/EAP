package com.eap.platform.observability;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class ObservationAlertReconciliationMySqlIT extends ObservationAlertReconciliationIntegrationTests {
    @DynamicPropertySource static void isolatedMySql(DynamicPropertyRegistry p) { ObservationAlertMySqlIT.isolatedMySql(p); }
}
