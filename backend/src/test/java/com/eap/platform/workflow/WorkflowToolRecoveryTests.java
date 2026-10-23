package com.eap.platform.workflow;

import com.eap.platform.registry.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowToolRecoveryTests {
    JdbcTemplate db;
    DataSourceTransactionManager manager;
    ScopedToolService tools;
    WorkflowToolRecoveryService service;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    ScopedToolDtos.RecoveryBinding binding=new ScopedToolDtos.RecoveryBinding("root","grant","holder",1,"uid","generation",1,"poc-counter-v1","fp");
    ScopedToolDtos.Execute request=new ScopedToolDtos.Execute("grant","operation","poc_test",1);
    @BeforeEach void setup()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:query_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tools=mock(ScopedToolService.class);
        db.execute("create table workflow_tool_recovery(id bigint primary key,execution_id bigint,sequence_number int,node_key varchar(128),task_key varchar(192),root_id varchar(36),operation_id varchar(36),binding_json text,request_json text,state varchar(24),reason varchar(256),owner_epoch bigint default 0,owner_id varchar(36),lease_until_ms bigint,next_check_ms bigint,observed_ms bigint,evidence_json text,created_ms bigint)");
        db.update("insert into workflow_tool_recovery(id,binding_json,request_json,state,reason,next_check_ms) values(1,?,?,'PENDING','original',0)",json.writeValueAsString(binding),json.writeValueAsString(request));
        service=fresh();
    }
    WorkflowToolRecoveryService fresh() { return new WorkflowToolRecoveryService(db,mock(WorkflowExecutionRepository.class),new WorkflowExecutionProperties(),tools,json,manager,mock(WorkflowToolEffectFence.class)); }
    @Test void processRestartReclaimsExpiredQueryButNeverReexecutesOriginalIntent() {
        var old=service.claim(1);assertThat(old).isNotNull();assertThat(fresh().claim(1)).isNull();
        db.update("update workflow_tool_recovery set lease_until_ms=0 where id=1");
        var replacement=fresh().claim(1);assertThat(replacement.epoch()).isEqualTo(old.epoch()+1);
        assertThat(service.finish(old,new ScopedToolDtos.RecoveryEvidence("UNKNOWN","stale",null))).isFalse();
        assertThat(fresh().finish(replacement,new ScopedToolDtos.RecoveryEvidence("CONFIRMED","original store fact",null))).isTrue();
        assertThat(service.finish(old,new ScopedToolDtos.RecoveryEvidence("UNKNOWN","late stale",null))).isFalse();
        assertThat(db.queryForObject("select state from workflow_tool_recovery where id=1",String.class)).isEqualTo("CONFIRMED");
        verifyNoInteractions(tools);
    }
    @Test void expiredLeaseCannotWriteEvenBeforeAnotherReaderClaims() {
        var old=service.claim(1);db.update("update workflow_tool_recovery set lease_until_ms=0 where id=1");
        assertThat(service.finish(old,new ScopedToolDtos.RecoveryEvidence("NO_COMMIT","expired",null))).isFalse();
        assertThat(db.queryForObject("select state from workflow_tool_recovery where id=1",String.class)).isEqualTo("CHECKING");
    }
    @Test void concurrentReadersAcquireOneQueryResponsibilityAndOneEpoch()throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var results=pool.invokeAll(java.util.Collections.nCopies(4,(java.util.concurrent.Callable<WorkflowToolRecoveryService.Claim>)()->fresh().claim(1)));
            long owners=0;for(var result:results)if(result.get()!=null)owners++;
            assertThat(owners).isEqualTo(1);assertThat(db.queryForObject("select owner_epoch from workflow_tool_recovery where id=1",Long.class)).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void failedStoreReadPreservesOriginalBindingAndSchedulesQueryOnly() {
        String original=db.queryForObject("select binding_json from workflow_tool_recovery where id=1",String.class);
        when(tools.recoverOriginal(binding,request)).thenThrow(new IllegalStateException("synthetic read interruption"));
        service.processPending();
        assertThat(db.queryForObject("select state from workflow_tool_recovery where id=1",String.class)).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("select binding_json from workflow_tool_recovery where id=1",String.class)).isEqualTo(original);
        assertThat(db.queryForObject("select next_check_ms from workflow_tool_recovery where id=1",Long.class)).isGreaterThan(System.currentTimeMillis());
        verify(tools).recoverOriginal(binding,request);verifyNoMoreInteractions(tools);
    }
}
