package com.eap.platform.gateway;

import org.springframework.http.HttpStatus;

public class GatewayException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public GatewayException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String admissionOutcome() {
        if(java.util.Set.of("team_proposal_rejected","team_execution_rejected","budget_exhausted","output_bound_required","ambiguous_output_bound","invalid_budget_task","task_identity_mismatch","invalid_invocation_key","experiment_not_running","optimization_not_running", "optimization_purpose_unapproved","workflow_not_running").contains(code))return "REJECTED";
        return java.util.Set.of("budget_invocation_recorded","budget_invocation_conflict").contains(code)?"RECORDED":"UNKNOWN";
    }
}
