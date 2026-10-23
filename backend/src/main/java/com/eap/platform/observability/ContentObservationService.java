package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

@Service
public class ContentObservationService {
    private final ContentObservationRepository repository;
    private final ObservationProperties properties;

    public ContentObservationService(ContentObservationRepository repository, ObservationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public ObservationProperties properties() {
        return properties;
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    @Transactional
    public ContentObservationDtos.ContentObservationResponse capture(ContentObservationDtos.CaptureRequest request) {
        return capture(new CaptureCommand(
                request.observationKey(), request.eventKey(), request.traceId(), request.spanId(),
                request.agentId(), request.endpointKey(), request.taskId(), request.provider(), request.modelName(),
                request.direction(), request.content(), request.redactedContent(), request.dataCategory(),
                request.sensitivity(), request.governanceAction(), request.observedAt()
        ));
    }

    @Transactional
    public ContentObservationDtos.ContentObservationResponse capture(CaptureCommand command) {
        if (!properties.isEnabled() || properties.getCaptureMode() == ContentCaptureMode.OFF) {
            return null;
        }
        ContentObservation existing = repository.findByObservationKey(command.observationKey()).orElse(null);
        if (existing != null) {
            return ContentObservationDtos.from(existing);
        }
        if (command.content() == null) {
            throw new BusinessException("深度观测内容不能为空");
        }
        String content = truncate(command.content(), properties.getMaxContentBytes());
        ContentObservation observation = new ContentObservation();
        observation.observationKey = normalize(command.observationKey(), 128);
        observation.eventKey = normalize(command.eventKey(), 128);
        observation.traceId = normalize(command.traceId(), 128);
        observation.spanId = normalize(command.spanId(), 128);
        observation.agentId = command.agentId();
        observation.endpointKey = normalize(command.endpointKey(), 128);
        observation.taskId = command.taskId();
        observation.provider = normalize(command.provider(), 128);
        observation.modelName = normalize(command.modelName(), 128);
        observation.direction = command.direction();
        observation.captureMode = properties.getCaptureMode();
        observation.contentHash = sha256(content);
        observation.contentBytes = (long) content.getBytes(StandardCharsets.UTF_8).length;
        observation.estimatedTokens = Math.max(0L, (content.length() + 3L) / 4L);
        observation.dataCategory = normalize(command.dataCategory(), 128);
        observation.sensitivity = normalize(command.sensitivity(), 32);
        observation.governanceAction = normalize(command.governanceAction(), 32);
        if (properties.getCaptureMode() == ContentCaptureMode.REDACTED && command.redactedContent() != null) {
            observation.contentJson = truncate(command.redactedContent(), properties.getMaxContentBytes());
            observation.redactionApplied = !command.redactedContent().equals(command.content());
        }
        observation.observedAt = command.observedAt() == null ? Instant.now() : command.observedAt();
        return ContentObservationDtos.from(repository.save(observation));
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<ContentObservationDtos.ContentObservationResponse> page(com.eap.platform.common.ObservationHistoryQuery query) {
        if(query.size>properties.getMaxListItems()) throw new com.eap.platform.common.BusinessException("每页条数超过内容查看配置上限");
        var pageable=query.pageable();
        var selected=query.enumStatus(ContentDirection.class);
        var spec=query.<ContentObservation>specification("direction", selected, "observationKey", "eventKey", "traceId", "provider", "modelName").and(query.observationContext());
        return com.eap.platform.common.HistoryPage.from(repository.findAll(spec,pageable).map(ContentObservationDtos::from));
    }

    @Transactional(readOnly = true)
    public List<ContentObservationDtos.ContentObservationResponse> list(int limit) {
        return list(limit, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<ContentObservationDtos.ContentObservationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String traceId,
            ContentDirection direction,
            Instant from,
            Instant to
    ) {
        return list(limit, agentId, endpointKey, traceId, direction, from, to, 0);
    }

    @Transactional(readOnly = true)
    public List<ContentObservationDtos.ContentObservationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String traceId,
            ContentDirection direction,
            Instant from,
            Instant to,
            int page
    ) {
        int boundedLimit = Math.max(1, Math.min(Math.min(limit, 500), properties.getMaxListItems()));
        List<ContentObservation> observations;
        Specification<ContentObservation> specification = Specification.where(null);
        if (agentId != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("agentId"), agentId));
        if (!blank(endpointKey)) specification = specification.and((root, query, cb) -> cb.equal(root.get("endpointKey"), endpointKey.trim()));
        if (!blank(traceId)) specification = specification.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId.trim()));
        if (direction != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("direction"), direction));
        if (from != null) specification = specification.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("observedAt"), from));
        if (to != null) specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("observedAt"), to));
        observations = repository.findAll(specification,
                    PageRequest.of(page, boundedLimit, Sort.by(Sort.Direction.DESC, "observedAt", "id"))).getContent();
        return observations.stream()
                .map(ContentObservationDtos::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ContentObservationDtos.ObservationStatus status() {
        List<ContentObservation> observations = repository.findTop500ByOrderByObservedAtDesc();
        long inputs = observations.stream().filter(item -> item.direction == ContentDirection.INPUT).count();
        long outputs = observations.stream().filter(item -> item.direction == ContentDirection.OUTPUT).count();
        return new ContentObservationDtos.ObservationStatus(
                properties.isEnabled(), properties.getCaptureMode(), properties.getMaxContentBytes(),
                repository.count(), inputs, outputs, observations.isEmpty() ? null : observations.get(0).observedAt
        );
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, long maxBytes) {
        if (value == null) return null;
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        int maxChars = (int) Math.min(value.length(), maxBytes);
        String candidate = value.substring(0, maxChars);
        while (candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes && !candidate.isEmpty()) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record CaptureCommand(
            String observationKey,
            String eventKey,
            String traceId,
            String spanId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String provider,
            String modelName,
            ContentDirection direction,
            String content,
            String redactedContent,
            String dataCategory,
            String sensitivity,
            String governanceAction,
            Instant observedAt
    ) {
    }
}
