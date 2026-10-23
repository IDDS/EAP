package com.eap.platform.optimization;

import com.eap.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/team-evolutions")
public class TeamEvolutionController {
    private final TeamEvolutionService service;
    private final TeamEvolutionAdmissionService admission;
    private final TeamEvolutionExecutionService execution;
    private final TeamEvolutionScoringService scoring;
    private final TeamEvolutionHoldoutService holdout;
    private final TeamEvolutionProposalService proposals;
    public TeamEvolutionController(TeamEvolutionService service,TeamEvolutionAdmissionService admission,TeamEvolutionExecutionService execution,TeamEvolutionScoringService scoring,TeamEvolutionHoldoutService holdout,TeamEvolutionProposalService proposals) { this.service = service; this.admission = admission; this.execution=execution;this.scoring=scoring;this.holdout=holdout;this.proposals=proposals; }
    private String actor(Authentication authentication) { return authentication == null ? "console" : authentication.getName(); }
    @GetMapping("/{id}/proposals") public ApiResponse<java.util.List<TeamEvolutionProposalService.Receipt>> proposals(@PathVariable long id,Authentication authentication) {return ApiResponse.ok(proposals.list(id,actor(authentication)));}
    @PostMapping("/{id}/proposals") public ApiResponse<TeamEvolutionProposalService.Receipt> proposal(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) {return ApiResponse.ok(proposals.propose(id,body,actor(authentication)));}
    @GetMapping("/{id}/proposals/{key}") public ApiResponse<TeamEvolutionProposalService.Receipt> proposal(@PathVariable long id,@PathVariable String key,Authentication authentication) {return ApiResponse.ok(proposals.get(id,key,actor(authentication)));}
    @GetMapping("/{id}/proposals/{key}/content") public ApiResponse<Map<String,Object>> proposalContent(@PathVariable long id,@PathVariable String key,Authentication authentication) {return ApiResponse.ok(proposals.content(id,key,actor(authentication)));}
    @GetMapping("/status") public ApiResponse<Map<String,Object>> status() { return ApiResponse.ok(service.status()); }
    @PostMapping public ApiResponse<TeamEvolutionService.Summary> submit(@RequestBody JsonNode body, Authentication authentication) { return ApiResponse.ok(service.submit(body,actor(authentication))); }
    @GetMapping("/{id}") public ApiResponse<TeamEvolutionService.Summary> get(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping("/{id}/content") public ApiResponse<Map<String,Object>> content(@PathVariable long id) { return ApiResponse.ok(service.content(id)); }
    @PostMapping("/{id}/candidates") public ApiResponse<TeamEvolutionService.Attempt> propose(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) { return ApiResponse.ok(service.propose(id,body,actor(authentication))); }
    @PostMapping("/{id}/evaluations") public ApiResponse<TeamEvolutionService.Evaluation> enroll(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) { return ApiResponse.ok(service.enroll(id,body,actor(authentication))); }
    @GetMapping("/{id}/evaluations/{evaluation}") public ApiResponse<TeamEvolutionService.Evaluation> evaluation(@PathVariable long id,@PathVariable String evaluation) { return ApiResponse.ok(service.evaluation(id,evaluation)); }
    @GetMapping("/{id}/budget") public ApiResponse<TeamEvolutionAdmissionService.Plan> budget(@PathVariable long id) { return ApiResponse.ok(admission.get(id)); }
    @PostMapping("/{id}/budget") public ApiResponse<TeamEvolutionAdmissionService.Plan> budget(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) { return ApiResponse.ok(admission.freeze(id,body,actor(authentication))); }
    @PostMapping("/{id}/budget/check") public ApiResponse<TeamEvolutionAdmissionService.Readiness> check(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) { return ApiResponse.ok(admission.check(id,body,actor(authentication))); }
    @GetMapping("/{id}/evaluations/{evaluation}/execution") public ApiResponse<TeamEvolutionExecutionService.Result> execution(@PathVariable long id,@PathVariable String evaluation) { return ApiResponse.ok(execution.get(id,evaluation)); }
    @PostMapping("/{id}/evaluations/{evaluation}/execution") public ApiResponse<TeamEvolutionExecutionService.Result> execute(@PathVariable long id,@PathVariable String evaluation,@RequestBody JsonNode body,Authentication authentication) { return ApiResponse.ok(execution.execute(id,evaluation,body,actor(authentication))); }
    @GetMapping("/{id}/evaluations/{evaluation}/score") public ApiResponse<TeamEvolutionScoringService.Score> score(@PathVariable long id,@PathVariable String evaluation) {return ApiResponse.ok(scoring.get(id,evaluation));}
    @PostMapping("/{id}/evaluations/{evaluation}/score") public ApiResponse<TeamEvolutionScoringService.Score> score(@PathVariable long id,@PathVariable String evaluation,@RequestBody JsonNode body,Authentication authentication) {return ApiResponse.ok(scoring.score(id,evaluation,body,actor(authentication)));}
    @GetMapping("/{id}/frontier") public ApiResponse<TeamEvolutionScoringService.Frontier> frontier(@PathVariable long id) {return ApiResponse.ok(scoring.frontier(id));}
    @PostMapping("/{id}/frontier") public ApiResponse<TeamEvolutionScoringService.Frontier> seal(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) {return ApiResponse.ok(scoring.seal(id,body,actor(authentication)));}
    @GetMapping("/{id}/holdout") public ApiResponse<TeamEvolutionHoldoutService.Holdout> holdout(@PathVariable long id) {return ApiResponse.ok(holdout.get(id));}
    @PostMapping("/{id}/holdout") public ApiResponse<TeamEvolutionHoldoutService.Holdout> holdout(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) {return ApiResponse.ok(holdout.freeze(id,body,actor(authentication)));}
    @GetMapping("/{id}/closure") public ApiResponse<TeamEvolutionHoldoutService.Closure> closure(@PathVariable long id) {return ApiResponse.ok(holdout.getClosure(id));}
    @PostMapping("/{id}/closure") public ApiResponse<TeamEvolutionHoldoutService.Closure> closure(@PathVariable long id,@RequestBody JsonNode body,Authentication authentication) {return ApiResponse.ok(holdout.close(id,body,actor(authentication)));}
}
