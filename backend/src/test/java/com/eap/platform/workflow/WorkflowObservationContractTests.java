package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowObservationContractTests {
    @Test void absentContractKeepsOneModelDefaultAndExplicitContractAcceptsOnlyZeroOrOne() {
        assertThat(read("{}")).isEqualTo(1);
        assertThat(read(contract("ONE"))).isEqualTo(1);
        assertThat(read(contract("NONE"))).isZero();
        assertThat(WorkflowObservationContract.expectedModels(contract("NONE"), "ARTIFACT", 1L)).isZero();
    }
    @Test void malformedAmbiguousAndExtendedContractsAreRejected() {
        for (String raw : List.of("null", "[]", "{\"eapObservation\":null}",
                "{\"eapObservation\":[]}", contract("UNKNOWN"), contract("none"),
                contract("NONE").replace("\"NONE\"", "0"),
                contract("NONE").replace("\"NONE\"", "\"NONE\",\"other\":1"),
                contract("NONE").replace("\"NONE\"", "\"NONE\",\"modelEvents\":\"ONE\""),
                contract("NONE").replace("EAP_OBSERVATION_V1", "FUTURE"),
                contract("NONE") + "{}")) {
            assertThatThrownBy(() -> read(raw)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void controlAxBuiltInToolAndUnboundNodesCannotSuppressModelExpectations() {
        for (String type : List.of("START", "END", "DECISION"))
            assertThatThrownBy(() -> WorkflowObservationContract.expectedModels(contract("NONE"), type, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WorkflowObservationContract.expectedModels(contract("NONE"), "AGENT", null)).isInstanceOf(BusinessException.class);
        for (String extra : List.of("\"executionBackend\":\"AX\"", "\"executionBackend\":null", "\"eapTool\":null", "\"eapTool\":{}"))
            assertThatThrownBy(() -> read("{" + extra + "," + contract("NONE").substring(1))).isInstanceOf(BusinessException.class);
    }
    private static String contract(String value) { return "{\"eapObservation\":{\"version\":\"EAP_OBSERVATION_V1\",\"modelEvents\":\"" + value + "\"}}"; }
    private static int read(String value) { return WorkflowObservationContract.expectedModels(value, "AGENT", 1L); }
}
