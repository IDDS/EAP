package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.eap.platform.config.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;

final class RuntimeRequestAuthentication {
    private RuntimeRequestAuthentication(){}
    static RuntimeConnectionService.Access authorize(RuntimeConnectionService connections,HttpServletRequest request){
        String token=request.getHeader("X-EAP-Runtime-Token"),bearer=request.getHeader("Authorization");
        if(token==null&&bearer!=null&&bearer.startsWith("Bearer "))token=bearer.substring(7);
        String key=request.getHeader("X-EAP-Runtime-Key");
        if(key==null||key.length()>128||token==null||token.length()>128)throw new UnauthorizedException("实例凭证缺失或无效");
        var access=connections.authenticate(key,token);
        String claimed=request.getHeader("X-EAP-Agent-Id");
        if(claimed!=null&&!access.agentId().toString().equals(claimed))throw new BusinessException("请求身份与实例凭证不一致");
        for(String header:new String[]{"X-EAP-Endpoint-Key","X-EAP-Task-Id","X-EAP-Governance-Decision-Key"})
            if(request.getHeader(header)!=null)throw new BusinessException("实例通道不接受覆盖归属或复用其他治理决策");
        connections.validateTaskProtocol(access,request.getRequestURI());
        return access;
    }
}
