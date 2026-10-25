package com.eap.platform.observability;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ToolInvocationService {
    private final ToolInvocationEventRepository repository;

    public ToolInvocationService(ToolInvocationEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ToolInvocationDtos.ToolInvocationResponse record(RecordCommand command) {
        ToolInvocationEvent event = repository.findByInvocationKey(command.invocationKey())
                .orElseGet(ToolInvocationEvent::new);
        event.invocationKey = normalize(command.invocationKey(), 128);
        event.traceId = prefer(command.traceId(), event.traceId, 128);
        event.spanId = prefer(command.spanId(), event.spanId, 128);
        event.parentSpanId = prefer(command.parentSpanId(), event.parentSpanId, 128);
        event.agentId = command.agentId() == null ? event.agentId : command.agentId();
        event.endpointKey = prefer(command.endpointKey(), event.endpointKey, 128);
        event.runtimeInstanceKey = prefer(command.runtimeInstanceKey(), event.runtimeInstanceKey, 128);
        event.taskId = command.taskId() == null ? event.taskId : command.taskId();
        event.observationSource = command.observationSource() == null
                ? valueOr(event.observationSource, ToolInvocationSource.OTLP) : command.observationSource();
        event.toolKind = command.toolKind() == null ? valueOr(event.toolKind, ToolInvocationKind.TOOL) : command.toolKind();
        event.toolName = prefer(command.toolName(), event.toolName, 256);
        if (event.toolName == null) event.toolName = "unknown-tool";
        event.mcpServerName = prefer(command.mcpServerName(), event.mcpServerName, 256);
        event.status = command.status() == null ? valueOr(event.status, ToolInvocationStatus.UNKNOWN) : command.status();
        event.durationMs = command.durationMs() == null ? valueOr(event.durationMs, 0L) : Math.max(0L, command.durationMs());
        event.inputObservationKey = prefer(command.inputObservationKey(), event.inputObservationKey, 128);
        event.outputObservationKey = prefer(command.outputObservationKey(), event.outputObservationKey, 128);
        event.attributesJson = TraceMetadata.project(command.attributesJson() == null ? event.attributesJson : command.attributesJson());
        event.observedAt = command.observedAt() == null ? valueOr(event.observedAt, Instant.now()) : command.observedAt();
        return ToolInvocationDtos.from(repository.save(event));
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<ToolInvocationDtos.ToolInvocationResponse> page(com.eap.platform.common.ObservationHistoryQuery query) {
        if(query.getEventKey()!=null && !query.getEventKey().isBlank()) throw new com.eap.platform.common.BusinessException("此类记录不支持eventKey筛选，请使用Trace ID");
        var pageable=query.pageable();
        var selected=query.enumStatus(ToolInvocationStatus.class);
        var spec=query.<ToolInvocationEvent>specification("status", selected, "invocationKey", "traceId", "toolName", "mcpServerName").and(query.observationContext());
        return com.eap.platform.common.HistoryPage.from(repository.findAll(spec,pageable).map(ToolInvocationDtos::from));
    }

    @Transactional(readOnly = true)
    public List<ToolInvocationDtos.ToolInvocationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String traceId,
            ToolInvocationKind toolKind,
            ToolInvocationStatus status,
            String toolName,
            Instant from,
            Instant to
    ) {
        return list(limit, agentId, endpointKey, runtimeInstanceKey, traceId, toolKind, status, toolName, from, to, 0);
    }

    @Transactional(readOnly = true)
    public List<ToolInvocationDtos.ToolInvocationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String traceId,
            ToolInvocationKind toolKind,
            ToolInvocationStatus status,
            String toolName,
            Instant from,
            Instant to,
            int page
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Specification<ToolInvocationEvent> specification = Specification.where(null);
        if (agentId != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("agentId"), agentId));
        if (!blank(endpointKey)) specification = specification.and((root, query, cb) -> cb.equal(root.get("endpointKey"), endpointKey.trim()));
        if (!blank(runtimeInstanceKey)) specification = specification.and((root, query, cb) -> cb.equal(root.get("runtimeInstanceKey"), runtimeInstanceKey.trim()));
        if (!blank(traceId)) specification = specification.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId.trim()));
        if (toolKind != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("toolKind"), toolKind));
        if (status != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (!blank(toolName)) specification = specification.and((root, query, cb) -> cb.like(cb.lower(root.get("toolName")), "%" + toolName.trim().toLowerCase() + "%"));
        if (from != null) specification = specification.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("observedAt"), from));
        if (to != null) specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("observedAt"), to));
        return repository.findAll(specification,
                        PageRequest.of(page, boundedLimit, Sort.by(Sort.Direction.DESC, "observedAt", "id")))
                .map(ToolInvocationDtos::from).getContent();
    }

    private String prefer(String candidate, String existing, int maxLength) {
        String normalized = normalize(candidate, maxLength);
        return normalized == null ? existing : normalized;
    }

    private String normalize(String value, int maxLength) {
        if (blank(value)) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private <T> T valueOr(T value, T fallback) {
        return value == null ? fallback : value;
    }

    public record RecordCommand(
            String invocationKey,
            String traceId,
            String spanId,
            String parentSpanId,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            Long taskId,
            ToolInvocationSource observationSource,
            ToolInvocationKind toolKind,
            String toolName,
            String mcpServerName,
            ToolInvocationStatus status,
            Long durationMs,
            String inputObservationKey,
            String outputObservationKey,
            String attributesJson,
            Instant observedAt
    ) {
    }
}
