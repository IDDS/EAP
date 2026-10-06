package com.eap.platform.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import com.eap.platform.common.BusinessException;

class WorkflowToolConfigTests {
    private String config(String numbers) {
        return "{\"eapTool\":{\"name\":\"calculate_sum\",\"arguments\":{\"numbers\":" + numbers + "}}}";
    }
    @Test void validBoundedNumbersAndUnrelatedNodeSettingsSurvive() {
        assertThat(WorkflowToolConfig.read(config("[-1e12,0,1e12,0.5]"), "AGENT", 1L).path("arguments").path("numbers").size()).isEqualTo(4);
        assertThat(WorkflowToolConfig.read("{\"suspendAfterTask\":true}", "AGENT", 1L)).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"[]", "[\"1\"]", "[null]", "[true]", "[1e13]", "[1e400]", "\"1,2\"", "null"})
    void invalidArgumentsCannotBeSavedOrDispatched(String numbers) {
        assertThatThrownBy(() -> WorkflowToolConfig.read(config(numbers), "AGENT", 1L)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsUnsupportedToolsExtraFieldsBackendsAndMissingIdentity() {
        String config = config("[1]");
        for (String bad : new String[]{config.replace("calculate_sum", "shell"), config.replace("\"numbers\":", "\"password\":\"marker\",\"numbers\":"),
                config.replace("\"eapTool\":", "\"executionBackend\":\"AX\",\"eapTool\":"), config("[" + "1,".repeat(100) + "1]")})
            assertThatThrownBy(() -> WorkflowToolConfig.read(bad, "AGENT", 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WorkflowToolConfig.read(config, "START", 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> WorkflowToolConfig.read(config, "AGENT", null)).isInstanceOf(BusinessException.class);
    }
    @Test void scopedCounterConfigurationRequiresStableIdentityAndBoundedParameters() {
        String valid="{\"eapTool\":{\"name\":\"controlled_counter_increment\",\"arguments\":{\"grantId\":\"00000000-0000-4000-8000-000000000001\",\"operationId\":\"00000000-0000-4000-8000-000000000002\",\"resourceId\":\"poc_test\",\"delta\":3}}}";
        assertThat(WorkflowToolConfig.read(valid,"AGENT",1L).path("name").asText()).isEqualTo("controlled_counter_increment");
        for(String bad:new String[]{valid.replace("poc_test","business_test"),valid.replace("\"delta\":3","\"delta\":101"),valid.replace("\"delta\":3","\"delta\":1.5"),valid.replace("\"delta\":3","\"effectSource\":\"forged\",\"delta\":3"),valid.replace("00000000-0000-4000-8000-000000000001","bad")})
            assertThatThrownBy(()->WorkflowToolConfig.read(bad,"AGENT",1L)).isInstanceOf(BusinessException.class);
    }

}
