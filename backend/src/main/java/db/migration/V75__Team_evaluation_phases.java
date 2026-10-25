package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.*;

/** Preserve old validation batches; replace only their unnamed candidate uniqueness. */
public class V75__Team_evaluation_phases extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 750001; }
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        boolean mysql=connection.getMetaData().getDatabaseProductName().equals("MySQL");
        // Discover the exact existing name after the column changes.
        try(var statement=connection.createStatement()) {
            statement.execute("alter table team_evolution_evaluations add column split_name varchar(16) not null default 'VALIDATION'");
            statement.execute("alter table team_evolution_evaluations add column arm varchar(16) not null default 'CANDIDATE'");
        }
        String old=null;
        String query="select tc.constraint_name,kcu.column_name,kcu.ordinal_position from information_schema.table_constraints tc join information_schema.key_column_usage kcu on tc.constraint_name=kcu.constraint_name and tc.table_schema=kcu.table_schema and tc.table_name=kcu.table_name where tc.table_name='team_evolution_evaluations' and tc.constraint_type='UNIQUE' and tc.table_schema="+(mysql?"database()":"'public'")+" order by tc.constraint_name,kcu.ordinal_position";
        var constraints=new LinkedHashMap<String,List<String>>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery(query)) {
            while(rows.next())constraints.computeIfAbsent(rows.getString(1),k->new ArrayList<>()).add(rows.getString(2));
        }
        for(var entry:constraints.entrySet())if(entry.getValue().equals(List.of("contract_id","candidate_id"))) {
            if(old!=null)throw new IllegalStateException("Ambiguous original team candidate constraint");old=entry.getKey();
        }
        if(old==null||!old.matches("[A-Za-z0-9_]+"))throw new IllegalStateException("Original team candidate constraint missing");
        try(var statement=connection.createStatement()) {
            // MySQL must keep a child index for its composite FK throughout the change.
            // H2 retains the old narrower UNIQUE index while that FK depends on it.
            if(!mysql)statement.execute("alter table team_evolution_evaluations drop constraint fk_team_evaluation_candidate");
            if(mysql)statement.execute("alter table team_evolution_evaluations add constraint uq_team_candidate_phase unique(contract_id,candidate_id,split_name,arm)");
            statement.execute("alter table team_evolution_evaluations drop "+(mysql?"index `"+old+"`":"constraint \""+old+"\""));
            if(!mysql) {
                statement.execute("alter table team_evolution_evaluations add constraint uq_team_candidate_phase unique(contract_id,candidate_id,split_name,arm)");
                statement.execute("alter table team_evolution_evaluations add constraint fk_team_evaluation_candidate foreign key(contract_id,candidate_id) references team_evolution_candidates(contract_id,candidate_id)");
            }
        }
    }
}
