package com.eap.platform.audit;
import com.eap.platform.common.HistoryQuery;
import com.eap.platform.common.GlobalExceptionHandler;
import com.eap.platform.operations.AgentTaskController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.workflow.execution.worker-enabled=false","eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
@Transactional
class HistoryPaginationTests {
    @Autowired AuditLogRepository repository;
    @Autowired AuditController controller;
    @Autowired AgentTaskController tasks;
    @Test void retrievesBeyondOldCapWithStableOrderAndLiteralSearch(){
        for(int i=0;i<115;i++){
            var row=new AuditLog();row.actor="history%_fixture";row.action="READ";row.resourceType="TEST";
            row.traceId="pagination-test";row.result=i%2==0?"SUCCESS":"FAILED";row.createdAt=Instant.parse("2025-01-01T00:00:00Z");repository.save(row);
        }
        repository.flush();
        var q=new HistoryQuery();q.keyword="history%_fixture";q.size=100;
        var first=controller.page(q).data();q.page=2;var second=controller.page(q).data();
        assertThat(first.totalElements()).isEqualTo(115);assertThat(second.items()).hasSize(15);
        assertThat(first.items()).extracting(AuditDtos.AuditLogResponse::id).doesNotContainAnyElementsOf(second.items().stream().map(AuditDtos.AuditLogResponse::id).toList());
        q.page=1;q.status="FAILED";assertThat(controller.page(q).data().totalElements()).isEqualTo(57);
        q.to=Instant.parse("2025-01-01T00:00:00Z");assertThat(controller.page(q).data().totalElements()).isZero();
    }
    @Autowired com.eap.platform.operations.AgentTaskService taskService;
    @Test void taskFiltersUseDatabaseAndReturnFullTotal(){
        for(int i=0;i<23;i++) taskService.create(new com.eap.platform.operations.AgentTaskDtos.TaskRequest(1L,null,"HISTORY_TEST","history-task-fixture", "pagination-requester"));
        var q=new HistoryQuery();q.keyword="history-task-fixture";q.size=10;q.page=3;q.status="PENDING";q.order="asc";
        var result=tasks.page(q).data();assertThat(result.totalElements()).isEqualTo(23);assertThat(result.items()).hasSize(3);
        q.status="FAILED";assertThat(tasks.page(q).data().totalElements()).isZero();
    }
    @Test void rejectsInvalidPagingAndFilters() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller,tasks).setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String path:new String[]{"/api/tasks/page","/api/audit-logs/page"}) {
            mvc.perform(get(path).param("size","101")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("page","0")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("order","invalid")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("status","INVALID")).andExpect(status().isBadRequest());
            mvc.perform(get(path).param("from","2026-01-02T00:00:00Z").param("to","2026-01-01T00:00:00Z")).andExpect(status().isBadRequest());
        }
    }
}
