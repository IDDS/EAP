package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @Transactional
class ObservationLogSearchIntegrationTests {
    @Autowired JdbcTemplate db;
    @Autowired ObservationLogSearchService logs;
    @Autowired ObservationCoverageService coverage;
    long operation(String entry, String trace) {
        String key="PUBLIC-LOG-"+UUID.randomUUID();
        db.update("insert into observation_operations(entry_kind,operation_key,trace_id,registration_state,expected_agent_spans,expected_model_events,runtime_status,created_at) values(?,?,?,'PENDING_IDENTITY',1,1,'EXECUTION_UNKNOWN',CURRENT_TIMESTAMP)",entry,key,trace);
        return db.queryForObject("select id from observation_operations where operation_key=?",Long.class,key);
    }
    void event(long id, String kind, String reason, Instant time) { db.update("insert into observation_operation_events values(?,?,?,NULL,?)",id,kind,reason,Timestamp.from(time)); }
    @Test void committedEventTextSearchLinksOriginalOperationAndTraceWithoutChangingUnknown() {
        long id=operation("CHAT","a".repeat(32)); event(id,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",Instant.now().minusSeconds(1));
        var before=coverage.detail(id);var result=logs.search("chat OUTCOME_UNCONFIRMED operation_id="+id,60,50);
        assertThat(result.logs()).singleElement().satisfies(row->{ assertThat(row.operationId()).isEqualTo(id);assertThat(row.traceId()).isEqualTo("a".repeat(32));assertThat(row.text()).contains("event=RECEIPT_CONFIRMED","reason=OUTCOME_UNCONFIRMED"); });
        assertThat(coverage.detail(id)).isEqualTo(before);assertThat(before.operation().executionStatus()).isEqualTo("EXECUTION_UNKNOWN");
        assertThat(result.sourceContract()).isEqualTo("PERSISTED_OPERATION_EVENT_TEXT_V1");
    }
    @Test void termsAreLiteralCaseInsensitiveAndMustAllMatchTheSameLog() {
        long id=operation("CHAT","b".repeat(32));event(id,"REGISTERED","ORIGINAL_INTENT_FROZEN",Instant.now().minusSeconds(2));event(id,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",Instant.now().minusSeconds(1));
        assertThat(logs.search("registered original_intent",60,50).logs()).hasSize(1);
        assertThat(logs.search("registered outcome_unconfirmed",60,50).logs()).isEmpty();
        assertThat(logs.search("original_intent",60,50).logs()).hasSize(1);assertThat(logs.search("original.intent",60,50).logs()).isEmpty();
        assertThatThrownBy(()->logs.search("%",60,50)).isInstanceOf(BusinessException.class);
    }
    @Test void stableOrderingUsesOriginalEventPrimaryKeyWhenTimestampsTie() {
        Instant time=Instant.now().minusSeconds(1);long a=operation("CHAT",null),b=operation("CHAT",null);
        event(a,"REGISTERED","ORIGINAL_INTENT_FROZEN",time);event(b,"REGISTERED","ORIGINAL_INTENT_FROZEN",time);event(b,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",time);
        assertThat(logs.search("chat",60,50).logs().stream().map(r->r.operationId()+":"+r.eventKind())).containsExactly(b+":REGISTERED",b+":RECEIPT_CONFIRMED",a+":REGISTERED");
    }
    @Test void matchLimitIsExplicitAndDoesNotBecomeAnOperationDenominator() {
        long id=operation("CHAT",null);Instant t=Instant.now().minusSeconds(1);event(id,"REGISTERED","ORIGINAL_INTENT_FROZEN",t);event(id,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",t);
        var r=logs.search("chat",60,1);assertThat(r.logs()).hasSize(1);assertThat(r.matchesTruncated()).isTrue();assertThat(r.scopeTruncated()).isFalse();assertThat(r.candidateLimit()).isEqualTo(10000);
    }
    @Test void originalEventWindowExcludesExpiredAndFutureEvents() {
        long id=operation("CHAT",null);event(id,"REGISTERED","ORIGINAL_INTENT_FROZEN",Instant.now().minusSeconds(7200));event(id,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",Instant.now().plusSeconds(7200));
        assertThat(logs.search("chat",60,50).logs()).isEmpty();assertThat(logs.search("registered",1440,50).logs()).hasSize(1);
    }
    @Test void unsupportedMetadataNeverLeaksCallerTextIntoTheSearchCorpus() {
        long id=operation("PUBLIC_CALLER_TEXT","PUBLIC_INVALID_TRACE");event(id,"PUBLIC_CALLER_EVENT","PUBLIC_CALLER_REASON",Instant.now().minusSeconds(1));
        var row=logs.search("unrecognized",60,50).logs().getFirst();assertThat(row.traceId()).isNull();assertThat(row.text()).doesNotContain("PUBLIC_CALLER").contains("UNRECOGNIZED_ENTRY","UNRECOGNIZED_EVENT","UNRECOGNIZED_REASON","trace=UNCONFIRMED");
        assertThat(logs.search("PUBLIC_CALLER",60,50).logs()).isEmpty();
    }
    @Test void operationKeysAndBusinessBodiesAreNotSearchableLogText() {
        long id=operation("CHAT",null);db.update("update observation_operations set operation_key='PUBLIC_BUSINESS_BODY_SENTINEL' where id=?",id);event(id,"REGISTERED","ORIGINAL_INTENT_FROZEN",Instant.now().minusSeconds(1));
        assertThat(logs.search("PUBLIC_BUSINESS_BODY_SENTINEL",60,50).logs()).isEmpty();assertThat(logs.search("chat",60,50).logs()).allSatisfy(r->assertThat(r.text()).doesNotContain("PUBLIC_BUSINESS_BODY_SENTINEL"));
    }
    @Test void candidateCapIsReportedEvenWhenAnOlderMatchingEventWasNotSearched() {
        long old=operation("CHAT",null);event(old,"RECEIPT_CONFIRMED","OUTCOME_UNCONFIRMED",Instant.now().minusSeconds(100));
        Instant recent=Instant.now().minusSeconds(1);
        for(int i=0;i<2001;i++) {long id=operation("WORKFLOW_CONTROL",null);for(String kind:List.of("REGISTERED","IDENTITY_FROZEN","DISPATCH_COMMITTED","RECEIPT_CONFIRMED","CONTROL_CHECKED"))event(id,kind,"PLATFORM_CHECK_PASSED",recent);}
        var r=logs.search("chat",60,50);assertThat(r.logs()).isEmpty();assertThat(r.scopeTruncated()).isTrue();assertThat(r.candidatesScanned()).isEqualTo(10000);
    }
    @Test void invalidSearchesAreRejectedWithoutEchoingUntrustedInput() {
        for(String query:Arrays.asList(null,"x".repeat(129),"x".repeat(65),"one two three four five","PUBLIC\nTEXT","' OR 1=1"))assertThatThrownBy(()->logs.search(query,60,50)).isInstanceOf(BusinessException.class).hasMessage("日志检索仅支持固定元数据字符，最多128字符和4个词");
        for(int window:List.of(0,10081))assertThatThrownBy(()->logs.search("",window,50)).isInstanceOf(BusinessException.class);
        for(int limit:List.of(0,101))assertThatThrownBy(()->logs.search("",60,limit)).isInstanceOf(BusinessException.class);
    }
    @Test void queryingMissingOrRolledBackEventsNeverEnrolsOrRepairsAnything() {
        long id=operation("CHAT",null);var before=coverage.detail(id);assertThat(logs.search("operation_id="+id,60,50).logs()).isEmpty();assertThat(coverage.detail(id)).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from observation_alert_heads where operation_id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("select count(*) from observation_operation_events where operation_id=?",Long.class,id)).isZero();
    }
}
