package com.eap.platform.ax;
import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/ax")
public class AxController {
 private final AxService service; public AxController(AxService service){this.service=service;}
 @GetMapping("/capabilities") public Object capabilities(){return ApiResponse.ok(service.capabilities());}
 @GetMapping("/tasks") public Object list(){return ApiResponse.ok(service.list());}
 @GetMapping("/tasks/{key}") public Object get(@PathVariable String key){return ApiResponse.ok(service.get(key));}
 @GetMapping("/tasks/{key}/content") public Object content(@PathVariable String key){return ApiResponse.ok(service.content(key));}
 @PostMapping("/tasks") public Object submit(@Valid @RequestBody AxService.Submit body,Authentication auth){return ApiResponse.ok(service.submit(body,auth==null?"console":requireSubject(auth)));}
 @PostMapping("/tasks/{key}/{action:suspend|resume}") public Object control(@PathVariable String key,@PathVariable String action,Authentication auth){return ApiResponse.ok(service.control(key,action,auth==null?"console":requireSubject(auth)));}
 private static String requireSubject(Authentication auth){
  if(auth.getName()==null||auth.getName().isBlank())throw new com.eap.platform.config.UnauthorizedException("身份令牌缺少用户标识，请重新登录");
  return auth.getName();
 }
}
