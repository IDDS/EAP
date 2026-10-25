package com.eap.platform.config;

import org.springframework.security.core.Authentication;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public enum PlatformPermission {
    VIEW_CONSOLE("viewConsole", "VIEWER", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    DEPLOY_AGENTS("deployAgents", "OPERATOR", "PLATFORM_ADMIN"),
    OPERATE_INSTANCES("operateInstances", "OPERATOR", "PLATFORM_ADMIN"),
    RUN_WORKFLOWS("runWorkflows", "OPERATOR", "PLATFORM_ADMIN"),
    GOVERNANCE("governance", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    MANAGE_SAFETY("manageSafety", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    VIEW_CONTENT("viewContent", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    APPROVE_CONTENT("approveContent", "PLATFORM_ADMIN", "DATA_OWNER"),
    MANAGE_EVALUATIONS("manageEvaluations", "OPERATOR", "PLATFORM_ADMIN"),
    ANNOTATE_EVALUATIONS("annotateEvaluations", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    MANAGE_FEEDBACK("manageFeedback", "OPERATOR", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    RUN_AGENT_EXPERIMENTS("runAgentExperiments", "PLATFORM_ADMIN"),
    REVIEW_EXPERIMENT_UNKNOWNS("reviewExperimentUnknowns", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN"),
    MANAGE_BUDGETS("manageBudgets", "GOVERNANCE_ADMIN", "PLATFORM_ADMIN");

    private final String capability;
    private final List<String> roles;

    PlatformPermission(String capability, String... roles) {
        this.capability = capability;
        this.roles = List.of(roles);
    }

    public String[] roles() {
        return roles.toArray(String[]::new);
    }

    public boolean allows(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> roles.stream().anyMatch(role -> authority.getAuthority().equals("ROLE_" + role)));
    }

    public static Map<String, Boolean> capabilities(Authentication authentication, boolean development) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        Arrays.stream(values()).forEach(permission -> result.put(permission.capability,
                development || permission.allows(authentication)));
        return result;
    }
}
