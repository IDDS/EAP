package com.eap.platform.optimization;

import java.util.function.Supplier;

/** An in-process capability; no HTTP header or Runtime credential can create it. */
public final class TeamProposalAccess {
    public record Permit(long contractId, String traceId) {}
    private static final ThreadLocal<Permit> CURRENT = new ThreadLocal<>();
    private TeamProposalAccess() {}
    public static Permit current() { return CURRENT.get(); }
    static <T> T call(long root, String trace, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("TEAM_PROPOSAL_NESTED_CALL");
        CURRENT.set(new Permit(root, trace));
        try { return action.get(); } finally { CURRENT.remove(); }
    }
}
