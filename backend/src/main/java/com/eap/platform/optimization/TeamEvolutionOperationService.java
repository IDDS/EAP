package com.eap.platform.optimization;

import com.eap.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Reconcile an original console request without resubmitting it or acquiring execution responsibility. */
@Service
public class TeamEvolutionOperationService {
    public enum Action { CREATE, CANDIDATE, ENROLL, BUDGET, EXECUTE, SCORE, FRONTIER, HOLDOUT, CLOSE }
    public record Context(boolean originalSubmitter, int maxTokens, Map<String,Object> availability) {}
    public record Receipt(Action action, String requestKey, Long rootId, String evaluationId, String state, Object result) {}
    private final JdbcTemplate db;
    private final TeamEvolutionService contracts;
    private final TeamEvolutionAdmissionService admission;
    private final TeamEvolutionExecutionService execution;
    private final TeamEvolutionScoringService scoring;
    private final TeamEvolutionHoldoutService holdout;

    public TeamEvolutionOperationService(JdbcTemplate db, TeamEvolutionService contracts,
            TeamEvolutionAdmissionService admission, TeamEvolutionExecutionService execution,
            TeamEvolutionScoringService scoring, TeamEvolutionHoldoutService holdout) {
        this.db=db; this.contracts=contracts; this.admission=admission;
        this.execution=execution; this.scoring=scoring; this.holdout=holdout;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Context context(long id, String actor) {
        var c=contracts.contract(id,false);
        return new Context(contracts.principal(actor).equals(c.get("principal_digest")),
                contracts.tree((String)c.get("protocol_json")).path("maxTokens").asInt(),contracts.status());
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Receipt receipt(Action action, String requestKey, Long rootId, String evaluationId, String actor) {
        if(action==null || requestKey==null || !requestKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))
            throw new BusinessException("TEAM_RECEIPT_QUERY_REJECTED");
        String key=requestKey.toLowerCase(Locale.ROOT);
        boolean batch=action==Action.EXECUTE || action==Action.SCORE;
        if(action==Action.CREATE) {
            if(rootId!=null || evaluationId!=null) throw new BusinessException("TEAM_RECEIPT_QUERY_REJECTED");
            String submission=contracts.hash(List.of(contracts.principal(actor),key));
            var ids=db.queryForList("select id from team_evolution_contracts where submission_key=?",Long.class,submission);
            if(ids.isEmpty()) return new Receipt(action,key,null,null,"NOT_FOUND",null);
            long id=ids.getFirst();
            return new Receipt(action,key,id,null,"FOUND",contracts.get(id));
        }
        if(rootId==null || rootId<1 || (batch ? evaluationId==null || !evaluationId.matches("[a-fA-F0-9-]{36}") : evaluationId!=null))
            throw new BusinessException("TEAM_RECEIPT_QUERY_REJECTED");
        contracts.owner(contracts.contract(rootId,false),actor);
        String evaluation=batch?evaluationId.toLowerCase(Locale.ROOT):null;
        if(batch) contracts.evaluation(rootId,evaluation);
        // The table and column are selected only from this closed enum; all caller values are bound.
        String table=switch(action) {
            case CANDIDATE -> "team_evolution_attempts";
            case ENROLL -> "team_evolution_evaluations";
            case BUDGET -> "team_evolution_budget_plans";
            case EXECUTE -> "team_evolution_executions";
            case SCORE -> "team_evolution_scores";
            case FRONTIER -> "team_evolution_frontiers";
            case HOLDOUT -> "team_evolution_holdouts";
            case CLOSE -> "team_evolution_closures";
            default -> throw new BusinessException("TEAM_RECEIPT_QUERY_REJECTED");
        };
        String keyColumn=(action==Action.CANDIDATE || action==Action.ENROLL)?"operation_key":"request_key";
        var rows=db.queryForList("select * from "+table+" where "+(batch?"evaluation_id":"contract_id")+"=? and "+keyColumn+"=?",batch?evaluation:rootId,key);
        if(rows.isEmpty()) return new Receipt(action,key,rootId,evaluation,"NOT_FOUND",null);
        var row=rows.getFirst();
        Object result=switch(action) {
            case CANDIDATE -> new TeamEvolutionService.Attempt((String)row.get("disposition"),(String)row.get("candidate_id"),(String)row.get("reason_code"));
            case ENROLL -> contracts.evaluation(rootId,(String)row.get("id"));
            case BUDGET -> admission.get(rootId);
            case EXECUTE -> execution.get(rootId,evaluation);
            case SCORE -> scoring.get(rootId,evaluation);
            case FRONTIER -> scoring.frontier(rootId);
            case HOLDOUT -> holdout.get(rootId);
            case CLOSE -> holdout.getClosure(rootId);
            default -> throw new BusinessException("TEAM_RECEIPT_QUERY_REJECTED");
        };
        // FOUND means a durable request record exists. RUNNING/UNKNOWN/REJECTED remain their original results.
        return new Receipt(action,key,rootId,evaluation,"FOUND",result);
    }
}
