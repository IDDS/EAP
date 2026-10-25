package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.Connection;
import java.util.*;

/** Historical uncertainty is carried forward, never silently treated as an idle actor. */
public class V86__Runtime_work_reclaim_fence extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 860001; }
    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (var s = c.createStatement()) {
            s.execute("create table runtime_work_actors(actor_key varchar(128) primary key,reclaim_state varchar(24) not null,reclaim_owner varchar(36))");
            s.execute("create table runtime_work_claims(work_id varchar(36) primary key,actor_key varchar(128) not null,work_kind varchar(24) not null,state varchar(16) not null,created_at timestamp(6) not null,updated_at timestamp(6) not null)");
            s.execute("create index ix_runtime_work_actor_state on runtime_work_claims(actor_key,state)");
        }
        // Only immutable identity bindings can locate old work; unbound registrations can be overwritten.
        Map<String,String> endpoints = new HashMap<>();
        try (var s = c.createStatement(); var r = s.executeQuery("select instance_key,endpoint from agent_runtime_instances where imported_actor_uid is not null or imported_actor_template is not null or platform_actor_uid is not null or platform_actor_template is not null")) {
            while (r.next()) endpoints.put(r.getString(1), r.getString(2));
        }
        Set<String> uncertain = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        try (var s = c.createStatement(); var r = s.executeQuery("select steps_json from workflow_executions where status in ('RUNNING','EXECUTION_UNKNOWN')")) {
            while (r.next()) {
                com.fasterxml.jackson.databind.JsonNode steps;
                try { steps = json.readTree(r.getString(1)); }
                catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { uncertain.add("*"); continue; }
                if (steps == null || !steps.isArray()) { uncertain.add("*"); continue; }
                for (var step : steps) {
                    if (!step.isObject() || !step.path("status").isTextual()) { uncertain.add("*"); continue; }
                    if (!Set.of("DISPATCHING", "EXECUTION_UNKNOWN").contains(step.path("status").asText())) continue;
                    String key = step.path("runtimeInstanceKey").asText();
                    if (key.startsWith("ax://")) continue; // AX owns a separate dedicated lifecycle.
                    add(uncertain, endpoints.get(key));
                }
            }
        }
        try (var s = c.createStatement(); var r = s.executeQuery("select c.instance_name,case when r.imported_actor_uid is not null or r.imported_actor_template is not null or r.platform_actor_uid is not null or r.platform_actor_template is not null then r.endpoint else null end from eap_chat_submissions c left join agent_runtime_instances r on r.agent_id=c.agent_id and r.instance_key like concat('%/',c.instance_name) where c.status in ('ACCEPTED','DISPATCHED','UNKNOWN')")) {
            while (r.next()) add(uncertain, r.getString(2));
        }
        try (var s = c.createStatement(); var r = s.executeQuery("select e.endpoint from agent_experiment_trials t join agent_experiments e on e.id=t.experiment_id where t.status in ('RUNNING','UNKNOWN')")) {
            while (r.next()) add(uncertain, r.getString(1));
        }
        try (var s = c.createStatement(); var r = s.executeQuery("select endpoint from team_evolution_dispatches where state in ('DISPATCHED','UNKNOWN')")) {
            while (r.next()) add(uncertain, r.getString(1));
        }
        try (var insert = c.prepareStatement("insert into runtime_work_claims values (?,?,'LEGACY','UNKNOWN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
            for (String actor : uncertain) {
                insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, actor); insert.executeUpdate();
            }
        }
    }
    private static void add(Set<String> actors, String endpoint) {
        if (endpoint == null) { actors.add("*"); return; } // Lost binding: cannot prove any existing actor idle.
        if (!endpoint.startsWith("substrate://")) return;
        String actor = endpoint.substring(12);
        if (actor.matches("[a-z0-9][a-z0-9-]{0,62}/[a-z0-9][a-z0-9-]{0,62}")) actors.add(actor);
        else actors.add("*");
    }
}
