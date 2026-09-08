package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.worker-enabled=false",
        "eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
class WorkflowEditingIntegrationTests {
    @Autowired WorkflowService workflows;

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "[]", "null", "true", "", "{} {}"})
    void rejectsInvalidConfigurationWithoutChangingSavedNode(String config) {
        Long id = create();
        workflows.upsertNode(id, node("start", "{\"saved\":true}"));
        assertThatThrownBy(() -> workflows.upsertNode(id, node("start", config)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("JSON 对象");
        assertThat(workflows.detail(id).nodes().getFirst().configJson()).isEqualTo("{\"saved\":true}");
    }

    @Test
    void boundsConfigurationByUtf8BytesBeforeDatabaseWrite() {
        Long id = create();
        String config = "{\"text\":\"" + "中".repeat(21000) + "\"}";
        assertThat(config.length()).isLessThan(60000);
        assertThatThrownBy(() -> workflows.upsertNode(id, node("start", config)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("60KB");
        assertThat(workflows.detail(id).nodes()).isEmpty();
    }

    @Test
    void concurrentNodeUpsertsAndIdenticalEdgesCreateOnlyOneRecord() throws Exception {
        Long id = create();
        var nodeIds = concurrent(() -> workflows.upsertNode(id, node("start", "{}" )).id());
        assertThat(nodeIds.stream().distinct().count()).isEqualTo(1);
        workflows.upsertNode(id, node("end", null));
        var edgeIds = concurrent(() -> workflows.addEdge(id, new WorkflowDtos.EdgeRequest("start", "end", "always")).id());
        assertThat(edgeIds.stream().distinct().count()).isEqualTo(1);
        assertThat(workflows.addEdge(id, new WorkflowDtos.EdgeRequest("start", "end", "  ")).id()).isEqualTo(edgeIds.getFirst());
        assertThat(workflows.detail(id).edges()).hasSize(1);
        workflows.addEdge(id, new WorkflowDtos.EdgeRequest("start", "end", "never"));
        assertThat(workflows.detail(id).edges()).hasSize(2);
    }

    @Test
    void staleEditorCannotOverwriteAnotherEditorsChanges() {
        Long id = create();
        var initial = workflows.upsertNode(id, node("start", "{}"));
        var changed = workflows.upsertNode(id, new WorkflowDtos.NodeRequest("start", "new name", "START",
                null, null, 10, 20, "{}", initial.version()));
        assertThat(changed.version()).isGreaterThan(initial.version());
        assertThatThrownBy(() -> workflows.upsertNode(id, new WorkflowDtos.NodeRequest("start", "stale name", "START",
                null, null, 0, 0, "{}", initial.version())))
                .isInstanceOf(com.eap.platform.common.ConflictException.class);
        assertThat(workflows.detail(id).nodes().getFirst().label()).isEqualTo("new name");
        var latest = workflows.upsertNode(id, new WorkflowDtos.NodeRequest("start", "reloaded", "START",
                null, null, 10, 20, "{}", changed.version()));
        assertThat(latest.label()).isEqualTo("reloaded");
    }

    private List<Long> concurrent(Callable<Long> action) throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { start.await(); return action.call(); }));
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (var future : futures) ids.add(future.get(10, TimeUnit.SECONDS));
            return ids;
        }
    }
    private Long create() {
        return workflows.create(new WorkflowDtos.WorkflowRequest("EDIT-" + UUID.randomUUID(), "edit test", null)).id();
    }
    private WorkflowDtos.NodeRequest node(String key, String config) {
        return new WorkflowDtos.NodeRequest(key, key, key.equals("start") ? "START" : "END", null, null, 0, 0, config);
    }
}
