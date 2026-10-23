package com.eap.platform.optimization;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;

/** Read-only console projection. Never calls admission checks, scoring writes or dispatch. */
@Service
public class TeamEvolutionConsoleService {
    private static final int PAGE_LIMIT = 20, SCAN_LIMIT = 200;
    private final JdbcTemplate db;
    private final AgentService agents;
    private final TeamEvolutionService contracts;
    private final TeamEvolutionAdmissionService admission;
    private final TeamEvolutionExecutionService execution;
    private final TeamEvolutionScoringService scoring;
    private final TeamEvolutionHoldoutService holdout;

    public TeamEvolutionConsoleService(JdbcTemplate db, AgentService agents, TeamEvolutionService contracts,
            TeamEvolutionAdmissionService admission, TeamEvolutionExecutionService execution,
            TeamEvolutionScoringService scoring, TeamEvolutionHoldoutService holdout) {
        this.db=db; this.agents=agents; this.contracts=contracts; this.admission=admission;
        this.execution=execution; this.scoring=scoring; this.holdout=holdout;
    }
    public record Row(long id, String createdAt, int candidates, int evaluations, boolean closed, String recommendation) {}
    public record Page(List<Row> items, Long nextBeforeId, int pageLimit, boolean scanLimited) {}
    public record Role(String role, long agentId) {}
    public record Evaluation(TeamEvolutionService.Evaluation registration,
                             TeamEvolutionExecutionService.Result execution, TeamEvolutionScoringService.Score score) {}
    public record Detail(TeamEvolutionService.Summary summary, String createdAt, List<Role> roles,
                         Map<String,Integer> sampleCounts, TeamEvolutionAdmissionService.Plan budget,
                         TeamEvolutionScoringService.Frontier frontier, TeamEvolutionHoldoutService.Holdout holdout,
                         TeamEvolutionHoldoutService.Closure closure, TeamEvolutionScoringService.Costs currentCosts,
                         List<Evaluation> evaluations) {}

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page list(Long beforeId) {
        if(beforeId!=null && beforeId<1) throw new BusinessException("TEAM_CONSOLE_CURSOR_REJECTED");
        var rows=beforeId==null
                ? db.queryForList("select id,protocol_json,created_at from team_evolution_contracts order by id desc limit ?",SCAN_LIMIT+1)
                : db.queryForList("select id,protocol_json,created_at from team_evolution_contracts where id<? order by id desc limit ?",beforeId,SCAN_LIMIT+1);
        var result=new ArrayList<Row>(); var visible=new HashMap<Long,Boolean>();
        int scanned=0; Long cursor=null;
        for(var row:rows) {
            if(scanned==SCAN_LIMIT || result.size()==PAGE_LIMIT) break;
            scanned++; long id=((Number)row.get("id")).longValue(); cursor=id;
            JsonNode roles=contracts.tree((String)row.get("protocol_json")).path("roles");
            if(!roles.isArray() || roles.size()!=2) throw new BusinessException("TEAM_RECORD_UNREADABLE");
            boolean allowed=true;
            for(var role:roles) {
                if(!role.path("agentId").isIntegralNumber() || !role.path("agentId").canConvertToLong() || role.path("agentId").asLong()<1) throw new BusinessException("TEAM_RECORD_UNREADABLE");
                if(!visible.computeIfAbsent(role.path("agentId").asLong(),agents::isVisible)) allowed=false;
            }
            if(!allowed) continue;
            var closure=exists("team_evolution_closures","contract_id",id)?holdout.getClosure(id):null;
            result.add(new Row(id,((Timestamp)row.get("created_at")).toInstant().toString(),
                    count("team_evolution_candidates",id),count("team_evolution_evaluations",id),
                    closure!=null,closure==null?null:closure.recommendation()));
        }
        boolean more=scanned<rows.size();
        return new Page(List.copyOf(result),more?cursor:null,PAGE_LIMIT,more && scanned==SCAN_LIMIT);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Detail get(long id) {
        var row=contracts.contract(id,false); var summary=contracts.get(id);
        var roles=new ArrayList<Role>();
        for(var role:contracts.tree((String)row.get("protocol_json")).path("roles"))
            roles.add(new Role(role.path("role").asText(),role.path("agentId").asLong()));
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(String split:List.of("TRAIN","VALIDATION","HOLDOUT")) counts.put(split,0);
        for(var sample:contracts.tree((String)row.get("dataset_json"))) counts.merge(sample.path("split").asText(),1,Integer::sum);
        var results=summary.evaluations().stream().map(e -> new Evaluation(e,
                exists("team_evolution_executions","evaluation_id",e.id())?execution.get(id,e.id()):null,
                exists("team_evolution_scores","evaluation_id",e.id())?scoring.get(id,e.id()):null)).toList();
        return new Detail(summary,((Timestamp)row.get("created_at")).toInstant().toString(),List.copyOf(roles),counts,
                exists("team_evolution_budget_plans","contract_id",id)?admission.get(id):null,scoring.frontier(id),
                exists("team_evolution_holdouts","contract_id",id)?holdout.get(id):null,
                exists("team_evolution_closures","contract_id",id)?holdout.getClosure(id):null,
                scoring.costs(id,false),results);
    }
    private int count(String table,long id) { return db.queryForObject("select count(*) from "+table+" where contract_id=?",Integer.class,id); }
    private boolean exists(String table,String column,Object id) { return db.queryForObject("select count(*) from "+table+" where "+column+"=?",Integer.class,id)>0; }
}
