package com.eap.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTests {
    @Test void planContractMigrationLeavesHistoricalPlansUnconfiguredAndUnchanged() throws Exception {
        String url="jdbc:h2:mem:plan_contract_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("88").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("update agent_plans set status='EXECUTION_UNKNOWN',execution_run_key='historical-plan-original' where id=1");
            q.executeUpdate("update agent_plan_steps set status='EXECUTION_UNKNOWN' where plan_id=1");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select status,execution_run_key from agent_plans where id=1")) {assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN");assertThat(r.getString(2)).isEqualTo("historical-plan-original");}
            try(var r=q.executeQuery("select status,output_contract_json from agent_plan_steps where plan_id=1")) {int count=0;while(r.next()){count++;assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN");assertThat(r.getString(2)).isNull();}assertThat(count).isGreaterThan(0);}
        }
    }
    @Test void placementMigrationDoesNotInventHistoricalLocations() throws Exception {
        String url="jdbc:h2:mem:placement_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("87").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into observation_operations(entry_kind,operation_key,registration_state,expected_agent_spans,expected_model_events,runtime_status,created_at) values('RUNTIME_TASK','historical-placement','PENDING_IDENTITY',1,0,'EXECUTION_UNKNOWN',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select count(*) from observation_runtime_placements")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=q.executeQuery("select runtime_status from observation_operations where operation_key='historical-placement'")){r.next();assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN");}
        }
    }
    @Test void sourceReferenceMigrationPreservesManualReferencesWithoutInferringChecks() throws Exception {
        String url="jdbc:h2:mem:source_reference_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("83").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into eap_evaluation_datasets(id,dataset_key,name,source_type,version,item_count,created_at,updated_at) values(91901,'LEGACY_SOURCE','legacy','MANUAL','v1',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into eap_evaluation_dataset_items(dataset_id,item_key,evaluator_key,trace_id,span_id,input_json,actual_json,ground_truth_label,created_at,updated_at) values(91901,'legacy-source','ACTION_COMPLETION','legacy-trace','legacy-span','{}','{}','PASS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement();var r=q.executeQuery("select trace_id,span_id,ground_truth_label,source_observation_id,source_endpoint_key,source_observed_at,source_checked_at from eap_evaluation_dataset_items where item_key='legacy-source'")) {
            assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("legacy-trace");
            assertThat(r.getString(2)).isEqualTo("legacy-span"); assertThat(r.getString(3)).isEqualTo("PASS");
            for (int i=4;i<=7;i++) assertThat(r.getObject(i)).isNull(); assertThat(r.next()).isFalse();
        }
    }

    @Test void approvalContextMigrationPreservesOldOutcomesWithoutInventingPolicyEvidence() throws Exception {
        String url="jdbc:h2:mem:approval_context_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("82").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into governance_data_events(id,event_key,direction,data_category,sensitivity,action,internal_data,company_secret,token_count,payload_bytes,observed_at,created_at) values(91001,'old-approval-event','INPUT_TO_MODEL','PUBLIC_FIXTURE','PUBLIC','APPROVAL_REQUIRED',false,false,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into governance_preflight_decisions(decision_key,data_event_id,action,gateway_directive,request_allowed,matched_policy_count,classifier,decided_at,approval_status,approved_by,request_fingerprint) values('old-approval',91001,'APPROVAL_REQUIRED','PROCEED_WITH_WARNING',true,1,'fixture',CURRENT_TIMESTAMP,'APPROVED','original-reviewer','original-fingerprint')");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement();var r=q.executeQuery("select approval_status,request_allowed,approved_by,request_fingerprint,policy_context_digest,revision from governance_preflight_decisions where decision_key='old-approval'")) {
            assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("APPROVED");assertThat(r.getBoolean(2)).isTrue();
            assertThat(r.getString(3)).isEqualTo("original-reviewer");assertThat(r.getString(4)).isEqualTo("original-fingerprint");
            assertThat(r.getString(5)).isNull();assertThat(r.getLong(6)).isZero();assertThat(r.next()).isFalse();
        }
    }

    @Test void proposalMigrationDoesNotGrantPurposeOrAlterOriginalBudget() throws Exception {
        String url="jdbc:h2:mem:proposal_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("81").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into team_evolution_contracts(id,submission_key,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,protocol_json,dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,created_at) values(1,'old-proposal','a','b','c','d','e','{}','[]','baseline',4,6,2,10000,CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into team_evolution_budget_plans(contract_id,plan_key,request_key,fingerprint,principal_digest,business_task_json,business_task_digest,max_calls,max_output_tokens,required_calls,approval_expires_at,live_bindings_json,created_at) values(1,'old-plan','old-request','original-fingerprint','b','{}','task',12,1536,12,CURRENT_TIMESTAMP,'[]',CURRENT_TIMESTAMP)");
        }
        var originalAccounts=budgetRows(url);
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        assertThat(budgetRows(url)).isEqualTo(originalAccounts);
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select proposal_mode,max_proposal_calls,max_proposal_tokens,max_calls,max_output_tokens,fingerprint from team_evolution_budget_plans where contract_id=1")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("OFFLINE_ONLY");assertThat(r.getInt(2)).isZero();assertThat(r.getInt(3)).isZero();
                assertThat(r.getLong(4)).isEqualTo(12);assertThat(r.getLong(5)).isEqualTo(1536);assertThat(r.getString(6)).isEqualTo("original-fingerprint");
            }
            for(String table:java.util.List.of("team_evolution_proposals","budget_reservations"))try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getInt(1)).isZero();}
        }
    }
    private java.util.List<java.util.List<Object>> budgetRows(String url) throws Exception {
        var rows=new java.util.ArrayList<java.util.List<Object>>();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement();var r=q.executeQuery("select * from budget_accounts order by id")) {
            while(r.next()){var row=new java.util.ArrayList<Object>();for(int n=1;n<=r.getMetaData().getColumnCount();n++)row.add(r.getObject(n));rows.add(row);}
        }
        return rows;
    }
    @Test void alertScanMigrationOnlyCreatesIdleCursor() throws Exception {
        String url="jdbc:h2:mem:alert_scan_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("78").load().migrate();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select sweep_number,upper_operation_id,last_operation_id,processed_count,failure_count from observation_alert_scan_cursor where id=1")) {
                assertThat(r.next()).isTrue();for(int i=1;i<=5;i++)assertThat(r.getLong(i)).isZero();assertThat(r.next()).isFalse();
            }
            for(String table:java.util.List.of("observation_alert_heads","observation_alerts","observation_alert_audit"))
                try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
        }
    }
    @Test void observationAlertsMigrationDoesNotEnrollOrResolveHistoricalUnknowns() throws Exception {
        String url="jdbc:h2:mem:alert_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("77").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into observation_operations(entry_kind,operation_key,registration_state,expected_agent_spans,expected_model_events,runtime_status,created_at) values('CHAT','historical-unknown','PENDING_IDENTITY',1,1,'EXECUTION_UNKNOWN',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            for(String table:java.util.List.of("observation_alert_heads","observation_alerts","observation_alert_audit"))
                try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select registration_state,runtime_status,expected_agent_spans,expected_model_events from observation_operations where operation_key='historical-unknown'")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("PENDING_IDENTITY");assertThat(r.getString(2)).isEqualTo("EXECUTION_UNKNOWN");
                assertThat(r.getInt(3)).isEqualTo(1);assertThat(r.getInt(4)).isEqualTo(1);assertThat(r.next()).isFalse();
            }
        }
    }
    @Test void holdoutMigrationPreservesOriginalUnknownValidationAndDoesNotSpend() throws Exception {
        String url="jdbc:h2:mem:team_holdout_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("74").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into team_evolution_contracts(id,submission_key,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,protocol_json,dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,created_at) values(1,'old-holdout','a','b','c','d','e','{}','[]','baseline',4,6,2,10000,CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into team_evolution_candidates(contract_id,candidate_id,generation,configuration_json,created_at) values(1,'baseline',0,'{}',CURRENT_TIMESTAMP)");
            q.executeUpdate("insert into team_evolution_evaluations(id,contract_id,candidate_id,operation_key,fingerprint,state,created_at) values('original',1,'baseline','old-eval','a','UNKNOWN',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select state,split_name,arm from team_evolution_evaluations where id='original'")){r.next();assertThat(r.getString(1)).isEqualTo("UNKNOWN");assertThat(r.getString(2)).isEqualTo("VALIDATION");assertThat(r.getString(3)).isEqualTo("CANDIDATE");}
            for(String table:java.util.List.of("team_evolution_holdouts","team_evolution_closures","team_evolution_slots","team_evolution_budget_plans","team_evolution_scores"))try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            for(String arm:java.util.List.of("BASELINE","SELECTED"))q.executeUpdate("insert into team_evolution_evaluations(id,contract_id,candidate_id,operation_key,fingerprint,state,created_at,split_name,arm) values('"+arm+"',1,'baseline','holdout-"+arm+"','a','NOT_DISPATCHED',CURRENT_TIMESTAMP,'HOLDOUT','"+arm+"')");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->q.executeUpdate("insert into team_evolution_evaluations(id,contract_id,candidate_id,operation_key,fingerprint,state,created_at) values('replacement',1,'baseline','replacement','a','NOT_DISPATCHED',CURRENT_TIMESTAMP)")).isInstanceOf(java.sql.SQLException.class);

        }
    }
    @Test void scoreMigrationNeverBackfillsOldReceiptsOrSelectsCandidates() throws Exception {
        String url="jdbc:h2:mem:team_score_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("73").load().migrate();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            for(String table:java.util.List.of("team_evolution_sample_responsibilities","team_evolution_scores","team_evolution_frontiers"))try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
        }
    }

    @Test void teamDispatchMigrationDoesNotClaimHistoricalSlotsOrAllocateBudgets() throws Exception {
        String url="jdbc:h2:mem:team_dispatch_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("72").load().migrate();
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            for(String table:java.util.List.of("team_evolution_executions","team_evolution_dispatches"))try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select count(*) from budget_accounts where scope_type='TASK'")){r.next();assertThat(r.getLong(1)).isZero();}
        }
    }
    @Test void teamBudgetMigrationNeverAllocatesOrChangesHistoricalContracts() throws Exception {
        String url="jdbc:h2:mem:team_budget_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("71").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into team_evolution_contracts(submission_key,fingerprint,principal_digest,protocol_digest,dataset_digest,validation_digest,protocol_json,dataset_json,baseline_id,max_candidates,max_attempts,max_generation,minimum_quality_bps,created_at) values('old-team','a','b','c','d','e','{\"executionState\":\"DISABLED_NO_EXECUTOR\"}','[]','f',4,6,2,10000,CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select count(*) from team_evolution_budget_plans")){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select count(*) from budget_accounts where scope_type='TASK'")){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select protocol_json from team_evolution_contracts where submission_key='old-team'")){r.next();assertThat(r.getString(1)).isEqualTo("{\"executionState\":\"DISABLED_NO_EXECUTOR\"}");}
        }
    }
    @Test
    void teamContractsDoNotEnrollHistoricalWorkflowOrOptimization() throws Exception {
        String url="jdbc:h2:mem:team_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("70").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values(1,'original-team-unknown','EXECUTION_UNKNOWN','original','owner','original-snapshot','original-steps',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            for(String table:java.util.List.of("team_evolution_contracts","team_evolution_candidates","team_evolution_attempts","team_evolution_evaluations","team_evolution_slots"))
                try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select status,snapshot_json,steps_json from workflow_executions where run_key='original-team-unknown'")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("EXECUTION_UNKNOWN");assertThat(r.getString(2)).isEqualTo("original-snapshot");assertThat(r.getString(3)).isEqualTo("original-steps");
            }
        }
    }
    @Test
    void diagnosticMigrationPreservesAllOldTrialsWithoutPolicyEnrollment() throws Exception {
        String url="jdbc:h2:mem:diagnostic_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("68").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into agent_experiments(agent_id,instance_key,actor_uid,template,endpoint,image_ref,deployment_version,submission_key,request_fingerprint,profile_digest,profile_json,samples_json,max_tokens,requested_by,status,created_at,updated_at) select id,'original-instance','original-uid','original-template','original-endpoint','original-image',1,'original-submission','a','b','{}','[]',64,'original-owner','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP from agents order by id limit 1");
            q.executeUpdate("insert into agent_experiment_variants(experiment_id,variant_name,system_prompt,config_digest) select id,'BASELINE','original-prompt','original-digest' from agent_experiments");
            q.executeUpdate("insert into agent_experiment_trials(experiment_id,variant_id,sample_index,status,task_key,trace_id,usage_source,reason_code) select experiment_id,id,0,'UNKNOWN','original-task','12345678901234567890123456789012','UNKNOWN','original-reason' from agent_experiment_variants");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            for(String table:java.util.List.of("experiment_diagnostic_decisions","experiment_diagnostic_receipts","observation_operations","observation_operation_events"))try(var r=q.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select status,task_key,reason_code from agent_experiment_trials")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("UNKNOWN");assertThat(r.getString(2)).isEqualTo("original-task");assertThat(r.getString(3)).isEqualTo("original-reason");}
            try(var r=q.executeQuery("select version,sample_basis_points from diagnostic_policy_versions")){r.next();assertThat(r.getLong(1)).isEqualTo(1);assertThat(r.getInt(2)).isEqualTo(10000);assertThat(r.next()).isFalse();}
        }
    }
    @Test
    void unknownReviewMigrationDoesNotApproveOrRewriteOriginalUnknown()throws Exception {
        String url="jdbc:h2:mem:unknown_review_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("67").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into agent_optimizations(agent_id,instance_key,submission_key,fingerprint,profile_json,dataset_json,dataset_digest,manual_prompt,engine,engine_version,max_tokens,max_metric_calls,max_calls,max_output_tokens,status,requested_by,reason_code,created_at,updated_at) select id,'old-instance','old-unknown-root','a','{}','[]','b','original-prompt','SIMPLE','old-engine',64,8,32,8192,'UNKNOWN','owner','INVALID_RECEIPT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP from agents order by id limit 1");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select count(*) from agent_optimization_unknown_reviews")){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=q.executeQuery("select status,reason_code,manual_prompt from agent_optimizations where submission_key='old-unknown-root'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("UNKNOWN");assertThat(r.getString(2)).isEqualTo("INVALID_RECEIPT");assertThat(r.getString(3)).isEqualTo("original-prompt");}
        }
    }
    @Test
    void optimizationBatchMigrationNeverEnrollsHistoricalRoots()throws Exception {
        String url="jdbc:h2:mem:optimization_migration_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("66").load().migrate();
        try(var c=java.sql.DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            q.executeUpdate("insert into agent_optimizations(agent_id,instance_key,submission_key,fingerprint,profile_json,dataset_json,dataset_digest,manual_prompt,engine,engine_version,max_tokens,max_metric_calls,max_calls,max_output_tokens,status,requested_by,created_at,updated_at) select id,'old-instance','old-root','a','{}','[]','b','old-prompt','SIMPLE','old-engine',64,8,32,8192,'FAILED','old-owner',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP from agents order by id limit 1");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=java.sql.DriverManager.getConnection(url,"sa","");var q=c.createStatement()) {
            try(var r=q.executeQuery("select count(*) from agent_optimization_protocols")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=q.executeQuery("select count(*) from agent_optimization_holdout_batches")){r.next();assertThat(r.getInt(1)).isZero();}
            try(var r=q.executeQuery("select manual_prompt,status from agent_optimizations where submission_key='old-root'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("old-prompt");assertThat(r.getString(2)).isEqualTo("FAILED");}
        }
    }

    @Test
    void newRecoveryMigrationPreservesLegacyUnknownIntentWithoutEnrollment() throws Exception {
        String url="jdbc:h2:mem:eap_legacy_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("63").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at) values(1,'old-unknown','EXECUTION_UNKNOWN','old','owner','original-snapshot','original-steps',CURRENT_TIMESTAMP)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement();var row=st.executeQuery("select status,snapshot_json,steps_json,recovery_version,owner_epoch,owner_id from workflow_executions where run_key='old-unknown'")) {
            assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("EXECUTION_UNKNOWN");
            assertThat(row.getString(2)).isEqualTo("original-snapshot");assertThat(row.getString(3)).isEqualTo("original-steps");
            assertThat(row.getInt(4)).isZero();assertThat(row.getLong(5)).isZero();assertThat(row.getString(6)).isNull();
        }
    }
    @Test
    void version64OriginalQueryIsNotSilentlyEnrolledInEffectFence()throws Exception {
        String url="jdbc:h2:mem:old_query_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("64").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,recovery_version,owner_epoch) values(1,'old-v64','EXECUTION_UNKNOWN','old','owner','original-snapshot','original-steps',CURRENT_TIMESTAMP,1,2)");
            st.executeUpdate("insert into workflow_tool_recovery(execution_id,sequence_number,node_key,task_key,root_id,operation_id,binding_json,request_json,state,reason,next_check_ms,created_ms) select id,0,'counter','original-task','original-root','original-O','original-binding','original-parameters','CONFIRMED','original-reason',0,0 from workflow_executions where run_key='old-v64'");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            try(var row=st.executeQuery("select count(*) from workflow_tool_effect_roots")){row.next();assertThat(row.getLong(1)).isZero();}
            try(var row=st.executeQuery("select state,binding_json,request_json,reason from workflow_tool_recovery")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("CONFIRMED");assertThat(row.getString(2)).isEqualTo("original-binding");
                assertThat(row.getString(3)).isEqualTo("original-parameters");assertThat(row.getString(4)).isEqualTo("original-reason");assertThat(row.next()).isFalse();
            }
        }
    }
    @Test
    void fairSchedulingMigrationDoesNotBackfillHistoricalExecutions()throws Exception {
        String url="jdbc:h2:mem:old_schedule_"+java.util.UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("65").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            st.executeUpdate("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,recovery_version,owner_epoch) values(1,'old-queue','QUEUED','old','original-owner','original-snapshot','original-steps',CURRENT_TIMESTAMP,1,2)");
        }
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var st=c.createStatement()) {
            for(String table:java.util.List.of("workflow_schedule_entries","workflow_schedule_groups","workflow_phase_observations"))try(var r=st.executeQuery("select count(*) from "+table)){r.next();assertThat(r.getLong(1)).isZero();}
            try(var r=st.executeQuery("select status,snapshot_json,steps_json,owner_epoch from workflow_executions where run_key='old-queue'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("QUEUED");assertThat(r.getString(2)).isEqualTo("original-snapshot");assertThat(r.getString(3)).isEqualTo("original-steps");assertThat(r.getLong(4)).isEqualTo(2);}
        }
    }
    @Test
    void appliesAllMigrationsAndSeedDataOnH2() throws Exception {
        String url = "jdbc:h2:mem:eap_migration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(91);
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var tables=statement.executeQuery("select count(*) from information_schema.tables where table_name in ('scoped_tool_lock','scoped_tool_grants','scoped_tool_counters','scoped_tool_operations')")) {
                tables.next();assertThat(tables.getLong(1)).isEqualTo(4);
            }
            try(var columns=statement.executeQuery("select count(*) from information_schema.columns where table_name='runtime_tool_calls' and column_name in ('evidence_source','effect_id','root_grant_id')")) {
                columns.next();assertThat(columns.getLong(1)).isEqualTo(3);
            }

            try (var tables = statement.executeQuery("select count(*) from information_schema.tables where table_name in "
                    + "('workflow_executions', 'eap_evaluation_calibration_runs', 'agentops_signal_sync_state')")) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery("select count(*) from information_schema.columns where table_name = 'trace_span_observations' and column_name in ('started_at', 'ended_at') and is_nullable = 'YES'")) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("select count(*) from agents")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'governance_preflight_decisions' and column_name = 'approval_status'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_control_commands' and column_name = 'runtime_instance_key'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery(
                    "select count(*) from agents where record_origin = 'DEMO'"
            )) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'trace_span_observations' and column_name = 'agent_id'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'tool_invocation_events'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name in ('provider_usage_snapshots', 'substrate_agent_deployments')"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(2);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'model_invocation_events' and column_name = 'usage_pool_key'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_tasks' and column_name in ('requester', 'problem_resolved', 'resolution_summary')"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(3);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'management_value_connectors' and column_name = 'task_id_path'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'management_value_events' and column_name = 'baseline_value'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'management_value_attributions'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'management_value_connectors'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluation_dataset_items'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'agent_runtime_instances' and column_name = 'dispatch_authenticated'"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(1);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluators'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("select count(*) from eap_evaluators")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(9);
            }
            try (var tables = statement.executeQuery(
                    "select count(*) from information_schema.tables where table_name = 'eap_evaluation_jobs'"
            )) {
                tables.next();
                assertThat(tables.getLong(1)).isEqualTo(1);
            }
            try (var columns = statement.executeQuery(
                    "select count(*) from information_schema.columns " +
                            "where table_name = 'eap_evaluation_results' and column_name in ('evaluator_version', 'request_fingerprint')"
            )) {
                columns.next();
                assertThat(columns.getLong(1)).isEqualTo(2);
            }
        }
    }
}
