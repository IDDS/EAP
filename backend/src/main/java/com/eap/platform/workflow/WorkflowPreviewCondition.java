package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded preview conditions; these inputs never grant execution authorization. */
final class WorkflowPreviewCondition {
    private static final List<String> SEVERITIES = List.of("low", "medium", "high", "critical");
    private static final Pattern SEVERITY = Pattern.compile("severity\\s*(>=|<=|==|>|<)\\s*(low|medium|high|critical)");

    private WorkflowPreviewCondition() {}

    static boolean isFallback(String expression) { return normalized(expression).equals("always"); }

    static void validate(String expression) {
        String condition = normalized(expression);
        if (!List.of("always", "never", "approved").contains(condition) && !SEVERITY.matcher(condition).matches()) {
            throw new BusinessException("不支持的预演条件；仅支持 always、never、approved 或 severity 与 low/medium/high/critical 比较");
        }
    }

    static boolean matches(String expression, Map<String, Object> context) {
        validate(expression);
        String condition = normalized(expression);
        if (condition.equals("always")) return true;
        if (condition.equals("never")) return false;
        Map<String, Object> values = context == null ? Map.of() : context;
        if (condition.equals("approved")) {
            if (!(values.get("approved") instanceof Boolean approved)) {
                throw new BusinessException("条件缺少布尔型预演上下文 approved；预演输入不代表治理审批");
            }
            return approved;
        }
        var matcher = SEVERITY.matcher(condition);
        matcher.matches();
        int actual = values.get("severity") instanceof String value
                ? SEVERITIES.indexOf(value.trim().toLowerCase(Locale.ROOT)) : -1;
        if (actual < 0) throw new BusinessException("条件缺少有效的预演上下文 severity");
        int expected = SEVERITIES.indexOf(matcher.group(2));
        return switch (matcher.group(1)) {
            case ">=" -> actual >= expected;
            case "<=" -> actual <= expected;
            case ">" -> actual > expected;
            case "<" -> actual < expected;
            default -> actual == expected;
        };
    }

    private static String normalized(String expression) {
        return expression == null || expression.isBlank() ? "always" : expression.trim().toLowerCase(Locale.ROOT);
    }
}
