package com.eap.platform.common;

import java.util.UUID;

public final class TraceContext {
    private TraceContext() {
    }

    public static String traceId() {
        return UUID.randomUUID().toString();
    }
}

