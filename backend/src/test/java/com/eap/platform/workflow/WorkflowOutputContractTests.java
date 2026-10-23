package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class WorkflowOutputContractTests {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String SCHEMA = """
        {"type":"object","properties":{"summary":{"type":"string"},"scores":{"type":"array","items":{"type":"integer"}}},"required":["summary"],"additionalProperties":false}
        """;
    static String config(String source, String schema) {
        return "{\"eapOutput\":{\"version\":\"EAP_JSON_V1\",\"source\":\"" + source + "\",\"schema\":" + schema + "}}";
    }
    static WorkflowOutputContract.Contract contract(String source) {
        return WorkflowOutputContract.read(config(source, SCHEMA), "AGENT", 1L);
    }
    @Test void preservesLegacyBehaviorWithoutPretendingItWasValidated() throws Exception {
        assertThat(WorkflowOutputContract.read("{}", "AGENT", 1L)).isNull();
        var value = JSON.readTree("{\"anything\":true}");
        var checked = WorkflowOutputContract.check(null, "SUCCEEDED", value);
        assertThat(checked.validation()).isNull(); assertThat(checked.accepted()).isSameAs(value);
    }
    @Test void acceptsTypedObjectsAndStrictJsonTextWithoutExposingContentInMetadata() throws Exception {
        for (String source : new String[]{"OUTPUT", "JSON_TEXT"}) {
            var value = JSON.readTree("{\"summary\":\"private-result\",\"scores\":[1,2]}");
            var input = source.equals("OUTPUT") ? value : JSON.getNodeFactory().textNode(value.toString());
            var checked = WorkflowOutputContract.check(contract(source), "SUCCEEDED", input);
            assertThat(checked.accepted()).isEqualTo(value);
            assertThat(checked.validation().status()).isEqualTo("PASSED");
            assertThat(JSON.writeValueAsString(checked.validation())).doesNotContain("private-result", "summary", "scores");
            assertThat(checked.validation().contractSha256()).matches("[a-f0-9]{64}");
        }
        assertThat(contract("OUTPUT").sha256()).isNotEqualTo(contract("JSON_TEXT").sha256());
    }
    @ParameterizedTest @ValueSource(strings={"```json\n{}\n```", "{} trailing", "{} {}", "{\"summary\":\"a\",\"summary\":\"b\"}", "", "   ", "[", "{\"summary\":NaN}"})
    void refusesAmbiguousOrNonJsonText(String raw) {
        var checked=WorkflowOutputContract.check(contract("JSON_TEXT"),"SUCCEEDED",JSON.getNodeFactory().textNode(raw));
        assertThat(checked.validation().code()).isEqualTo("INVALID_JSON"); assertThat(checked.accepted()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"{}", "[]", "null", "{\"summary\":1}", "{\"summary\":null}", "{\"summary\":\"a\",\"extra\":true}", "{\"summary\":\"a\",\"scores\":[1,1.5]}", "{\"summary\":\"a\",\"scores\":[1.0]}"})
    void rejectsMissingWrongTypesAndUnexpectedFields(String raw) throws Exception {
        var checked=WorkflowOutputContract.check(contract("OUTPUT"),"SUCCEEDED",JSON.readTree(raw));
        assertThat(checked.validation().status()).isEqualTo("REJECTED"); assertThat(checked.accepted()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","EXECUTION_UNKNOWN","DISPATCHING"})
    void neverClaimsUnknownOrFailedReceiptsPassEvenWithAValidLookingBody(String state) throws Exception {
        var checked=WorkflowOutputContract.check(contract("OUTPUT"),state,JSON.readTree("{\"summary\":\"ok\"}"));
        assertThat(checked.validation().status()).isEqualTo("NOT_EVALUATED"); assertThat(checked.accepted()).isNull();
    }
    @Test void requiresTheExplicitSourceAndDoesNotCoerceTypes() throws Exception {
        assertThat(WorkflowOutputContract.check(contract("JSON_TEXT"),"SUCCEEDED",JSON.readTree("{}")) .validation().code()).isEqualTo("TEXT_REQUIRED");
        assertThat(WorkflowOutputContract.check(contract("OUTPUT"),"SUCCEEDED",JSON.getNodeFactory().textNode("{}")) .validation().code()).isEqualTo("TYPE_MISMATCH");
        assertThat(WorkflowOutputContract.check(contract("OUTPUT"),"SUCCEEDED",null).validation().code()).isEqualTo("OUTPUT_MISSING");
    }
    @Test void validatesNestedObjectsArraysOptionalFieldsAndExplicitNull() throws Exception {
        String s="""
            {"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"ok":{"type":"boolean"},"empty":{"type":"null"},"amount":{"type":"number"}},"required":["ok","amount"],"additionalProperties":true}}},"required":["items"],"additionalProperties":false}
            """;
        var c=WorkflowOutputContract.read(config("JSON_TEXT",s),"ARTIFACT",1L);
        var passed=WorkflowOutputContract.check(c,"SUCCEEDED",JSON.getNodeFactory().textNode("{\"items\":[{\"ok\":true,\"empty\":null,\"extra\":42,\"amount\":0.1234567890123456789}]}"));
        assertThat(passed.validation().status()).isEqualTo("PASSED");
        assertThat(passed.accepted().path("items").get(0).path("amount").decimalValue().toPlainString()).isEqualTo("0.1234567890123456789");
        var failed=WorkflowOutputContract.check(c,"SUCCEEDED",JSON.getNodeFactory().textNode("{\"items\":[{\"ok\":\"true\",\"amount\":1}]}"));
        assertThat(failed.validation().status()).isEqualTo("REJECTED");
    }
    @ParameterizedTest @ValueSource(strings={"{\"type\":\"object\"}","{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", "{\"type\":\"object\",\"properties\":{},\"required\":[\"absent\"],\"additionalProperties\":false}","{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false,\"$ref\":\"https://example.invalid\"}"})
    void rejectsUnsupportedSchemasAtConfigurationTime(String schema) {
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",schema),"AGENT",1L)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsUnknownKeywordsVersionsDuplicateKeysAndUnsupportedBackends() {
        for(String bad:new String[]{config("UNKNOWN",SCHEMA),config("OUTPUT",SCHEMA).replace("EAP_JSON_V1","JSON_SCHEMA"),
                config("OUTPUT",SCHEMA).replace("\"type\":\"string\"","\"type\":\"string\",\"pattern\":\".*\""),
                config("OUTPUT",SCHEMA).replace("\"source\":\"OUTPUT\"","\"source\":\"OUTPUT\",\"source\":\"JSON_TEXT\""),
                config("OUTPUT",SCHEMA).replaceFirst("\\{","{\"executionBackend\":\"AX\","),config("OUTPUT",SCHEMA)+" {}"}) {
            assertThatThrownBy(()->WorkflowOutputContract.read(bad,"AGENT",1L)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",SCHEMA),"START",1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",SCHEMA),"AGENT",null)).isInstanceOf(BusinessException.class);
    }
    @Test void boundsDepthNodeCountPropertyCountAndBytesBeforeDispatch() {
        var deep=JSON.createObjectNode().put("type","string");
        for(int i=0;i<8;i++) {var parent=JSON.createObjectNode().put("type","array");parent.set("items",deep);deep=parent;}
        var root=JSON.createObjectNode().put("type","object").put("additionalProperties",false);root.putArray("required");root.putObject("properties").set("deep",deep);
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",root.toString()),"AGENT",1L)).isInstanceOf(BusinessException.class);
        var props=root.putObject("properties");for(int i=0;i<65;i++)props.putObject("p"+i).put("type","string");
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",root.toString()),"AGENT",1L)).isInstanceOf(BusinessException.class);
        props=root.putObject("properties");for(int i=0;i<64;i++)props.putObject("p"+i).put("type","array").putObject("items").put("type","string");
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",root.toString()),"AGENT",1L)).isInstanceOf(BusinessException.class);
        props=root.putObject("properties");for(int i=0;i<64;i++)props.putObject("x".repeat(125)+i).put("type","string");
        assertThatThrownBy(()->WorkflowOutputContract.read(config("OUTPUT",root.toString()),"AGENT",1L)).isInstanceOf(BusinessException.class);
    }
}
