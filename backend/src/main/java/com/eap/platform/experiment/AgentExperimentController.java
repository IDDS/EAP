package com.eap.platform.experiment;
import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import java.util.List;
@RestController @RequestMapping("/api/agent-experiments")
public class AgentExperimentController {
 private final AgentExperimentService service;
 public AgentExperimentController(AgentExperimentService service){this.service=service;}
 @GetMapping("/targets") public ApiResponse<AgentExperimentService.Targets> targets(@RequestParam Long agentId){return ApiResponse.ok(service.targets(agentId));}
 @PostMapping("/profile") public ApiResponse<AgentExperimentDtos.Profile> profile(@Valid @RequestBody AgentExperimentDtos.Target r){return ApiResponse.ok(service.profile(r));}
 @PostMapping public ApiResponse<AgentExperimentDtos.Summary> submit(@Valid @RequestBody AgentExperimentDtos.Submit r,Authentication a){return ApiResponse.ok(service.submit(r,a==null?"console":a.getName()));}
 @GetMapping public ApiResponse<List<AgentExperimentDtos.Summary>> list(){return ApiResponse.ok(service.list());}
 @GetMapping("/{id}") public ApiResponse<AgentExperimentDtos.Summary> get(@PathVariable Long id){return ApiResponse.ok(service.get(id));}
 @GetMapping("/{id}/content") public ApiResponse<AgentExperimentDtos.Content> content(@PathVariable Long id){return ApiResponse.ok(service.content(id));}
}
