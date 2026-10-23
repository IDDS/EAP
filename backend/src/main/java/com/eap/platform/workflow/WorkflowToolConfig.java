package com.eap.platform.workflow;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;

/** Validated, frozen per-node tool intent. No expressions, arbitrary tools or model fallback. */
final class WorkflowToolConfig {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static JsonNode read(String configJson, String nodeType, Long agentId) {
        try {
            JsonNode config = JSON.readTree(configJson == null ? "{}" : configJson);
            if (config == null || !config.isObject()) throw new IllegalArgumentException();
            JsonNode tool = config.get("eapTool");
            if (tool == null) return null;
            if (agentId == null || nodeType == null || !Set.of("AGENT", "ARTIFACT").contains(nodeType.toUpperCase(Locale.ROOT)))
                throw new BusinessException("工具任务必须绑定智能体，并使用智能体或制品节点");
            if (config.has("executionBackend") && !"RUNTIME".equals(config.path("executionBackend").asText()))
                throw new BusinessException("工具任务仅支持常规执行；隔离沙箱尚未接入此工具协议");
            if(tool.isObject()&&tool.size()==2&&"controlled_counter_increment".equals(tool.path("name").asText())) {
                JsonNode args=tool.path("arguments");
                if(!args.isObject()||args.size()!=4||!uuid(args.path("grantId").asText())||!uuid(args.path("operationId").asText())||!args.path("resourceId").asText().matches("poc_[A-Za-z0-9_-]{1,60}")||!args.path("delta").isIntegralNumber()||!args.path("delta").canConvertToLong()||args.path("delta").asLong()<1||args.path("delta").asLong()>100)
                    throw new BusinessException("计数工具需要授权、稳定操作标识、测试对象及1–100整数增量");
                return tool;
            }
            if (!tool.isObject() || tool.size() != 2 || !"calculate_sum".equals(tool.path("name").asText()))
                throw new BusinessException("当前仅支持求和计算工具，不接受其他工具或附加字段");
            JsonNode args = tool.path("arguments"), numbers = args.path("numbers");
            if (!args.isObject() || args.size() != 1 || !numbers.isArray() || numbers.isEmpty() || numbers.size() > 100)
                throw new BusinessException("求和参数必须包含 1–100 个数字，且仅允许 numbers 字段");
            for (JsonNode number : numbers) {
                double value = number.asDouble();
                if (!number.isNumber() || !Double.isFinite(value) || Math.abs(value) > 1e12)
                    throw new BusinessException("求和参数必须为有限数字，绝对值不超过 10^12");
            }
            return tool;
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw new BusinessException("节点配置必须是有效的 JSON 对象"); }
    }
    private static boolean uuid(String id){try{return java.util.UUID.fromString(id).toString().equals(id);}catch(Exception e){return false;}}
    static JsonNode read(WorkflowDtos.NodeResponse node) {
        return read(node.configJson(), node.nodeType(), node.agentId());
    }
}
