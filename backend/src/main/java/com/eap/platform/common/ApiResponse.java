package com.eap.platform.common;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String traceId
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, "ok", TraceContext.traceId());
    }
}

