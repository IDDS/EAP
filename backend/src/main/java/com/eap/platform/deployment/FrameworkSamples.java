package com.eap.platform.deployment;

import com.eap.platform.common.BusinessException;
import java.util.List;

/** Versioned, fixed-code demonstrations, not a generic framework hosting contract. */
final class FrameworkSamples {
    record Sample(String key, String label, String frameworkVersion, String command) {}

    static final List<Sample> ALL = List.of(
            new Sample("LANGGRAPH_SUM_V1", "LangGraph 固定求和", "1.2.17", "/usr/local/bin/eap-langgraph-runner"),
            new Sample("AUTOGEN_SUM_V1", "AutoGen Core 固定求和", "0.7.5", "/usr/local/bin/eap-autogen-runner"));

    static Sample require(String key) {
        return ALL.stream().filter(sample -> sample.key().equals(key)).findFirst()
                .orElseThrow(() -> new BusinessException("请选择已支持的受控框架样例"));
    }

    private FrameworkSamples() {}
}
