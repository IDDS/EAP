package com.eap.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TraceMetadataSecurityTests {
    @Test void rejectsBodyHeadersExceptionsUnknownFieldsAndWrongTypesWhileKeepingCompletionMetadata() throws Exception {
        String input = """
            {"rag.query":"PRIVATE_RAG","mcp.request":{"value":"PRIVATE_MCP"},
             "http.request.header.authorization":"PRIVATE_HEADER","exception.message":"PRIVATE_ERROR",
             "custom.field":"PRIVATE_OTHER","tool.name":{"name":"PRIVATE_OBJECT"},
             "eap.model.finish_reason":"stop","eap.model.response_state":"COMPLETE",
             "eap.model.request.generation_limit":"1024","eap.model.request.thinking_mode":"DISABLED"}
            """;
        String safe = TraceMetadata.project(input);
        assertThat(safe).doesNotContain("PRIVATE", "rag.query", "authorization", "tool.name");
        assertThat(new ObjectMapper().readTree(safe).path("eap.model.request.generation_limit").asText()).isEqualTo("1024");
        assertThat(safe).contains("stop", "COMPLETE", "DISABLED");
        assertThat(TraceMetadata.project(safe)).isEqualTo(safe);
        assertThat(TraceMetadata.project("{invalid")).isEqualTo("{}");
    }
    @Test void projectsLegacyToolAttributesWithoutRewritingTheStoredEvidence() {
        var event = new ToolInvocationEvent(); event.attributesJson = "{\"mcp.response\":\"PRIVATE_OLD\",\"tool.name\":\"search\"}";
        assertThat(ToolInvocationDtos.from(event).attributesJson()).doesNotContain("PRIVATE_OLD").contains("search");
        assertThat(event.attributesJson).contains("PRIVATE_OLD");
    }
}
