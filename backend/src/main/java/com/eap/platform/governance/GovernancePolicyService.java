package com.eap.platform.governance;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static com.eap.platform.governance.GovernancePolicyDtos.*;

@Service
public class GovernancePolicyService {
    private final GovernancePolicyRepository repository;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate db;

    public GovernancePolicyService(GovernancePolicyRepository repository, ObjectMapper objectMapper, JdbcTemplate db) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<GovernancePolicyDtos.PolicyResponse> list() {
        return repository.findAll().stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public GovernancePolicyDtos.PolicyResponse create(GovernancePolicyDtos.PolicyRequest request) {
        if (request.expectedRevision() != null) throw new BusinessException("新建策略不接受原版本");
        GovernancePolicy policy = new GovernancePolicy();
        apply(policy, request);
        policy = repository.saveAndFlush(policy);
        record(policy.getId(), policy.getRevision(), "CREATED", null, values(policy));
        return response(policy);
    }

    @Transactional
    public GovernancePolicyDtos.PolicyResponse update(Long id, GovernancePolicyDtos.PolicyRequest request) {
        GovernancePolicy policy = original(id, request.expectedRevision());
        long beforeRevision = policy.getRevision();
        PolicyValues before = values(policy);
        GovernancePolicy proposed = new GovernancePolicy();
        apply(proposed, request);
        if (before.equals(values(proposed))) return response(policy);
        copy(proposed, policy);
        return publish(policy, beforeRevision, before, "UPDATED", null);
    }

    @Transactional
    public PolicyResponse rollback(Long id, RollbackRequest request) {
        GovernancePolicy policy = original(id, request.expectedRevision());
        if (request.sourceRevision() == null || request.sourceRevision() < 0
                || request.sourceRevision() >= policy.getRevision() || request.sourceDigest() == null
                || !request.sourceDigest().matches("[a-f0-9]{64}"))
            throw new BusinessException("回退必须指定此前已记录的版本及摘要");
        var versions = db.query("select * from governance_policy_versions where policy_id=? and revision=?",
                this::version, id, request.sourceRevision());
        if (versions.size() != 1) throw new BusinessException("指定策略版本未记录，不能推测历史规则");
        PolicyVersion source = versions.getFirst();
        if (!source.snapshotDigest().equals(request.sourceDigest())) throw new ConflictException("回退目标摘要不一致，请重新核对");
        long beforeRevision = policy.getRevision();
        PolicyValues before = values(policy), target = source.values();
        GovernancePolicy proposed = new GovernancePolicy();
        apply(proposed, new PolicyRequest(target.name(), target.policyType(), target.scopeType(), target.scopeValue(),
                target.ruleJson(), target.enforcementMode(), target.enabled(), beforeRevision));
        if (before.equals(values(proposed))) throw new BusinessException("目标版本与当前策略相同，无需回退");
        copy(proposed, policy);
        return publish(policy, beforeRevision, before, "ROLLED_BACK", source.revision());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionHistory versions(Long id) {
        GovernancePolicy policy = repository.findById(id).orElseThrow(() -> new BusinessException("治理策略不存在"));
        var rows = db.query("select * from governance_policy_versions where policy_id=? and revision<=? order by revision desc limit 51",
                this::version, id, policy.getRevision());
        var origin = db.queryForList("select change_kind from governance_policy_versions where policy_id=? and revision=0", String.class, id);
        return new VersionHistory(id, policy.getRevision(), !origin.equals(List.of("CREATED")), rows.size() > 50,
                rows.stream().limit(50).toList());
    }

    private GovernancePolicy original(Long id, Long expected) {
        if (expected == null || expected < 0) throw new BusinessException("更新策略必须携带读取时的 expectedRevision");
        GovernancePolicy policy = repository.findById(id).orElseThrow(() -> new BusinessException("治理策略不存在"));
        if (policy.getRevision() != expected) throw new ConflictException("治理策略已更新，请重新读取后再编辑");
        if (expected == Long.MAX_VALUE) throw new BusinessException("策略版本已达上限");
        return policy;
    }

    private PolicyResponse publish(GovernancePolicy policy, long beforeRevision, PolicyValues before, String kind, Long source) {
        // Flush the optimistic version update first. A losing writer rolls back before any history is inserted.
        policy = repository.saveAndFlush(policy);
        if (policy.getRevision() != beforeRevision + 1) throw new ConflictException("策略版本未按预期推进");
        if (beforeRevision == 0 && db.queryForObject("select count(*) from governance_policy_versions where policy_id=? and revision=0",
                Long.class, policy.getId()) == 0) record(policy.getId(), 0, "BASELINE", null, before);
        record(policy.getId(), policy.getRevision(), kind, source, values(policy));
        return response(policy);
    }

    private PolicyValues values(GovernancePolicy policy) {
        return new PolicyValues(policy.getName(), policy.getPolicyType(), policy.getScopeType(), policy.getScopeValue(),
                normalizedRuleJson(policy.getRuleJson()), policy.getEnforcementMode(), policy.isEnabled());
    }

    private void record(Long id, long revision, String kind, Long source, PolicyValues values) {
        try {
            String snapshot = objectMapper.writeValueAsString(values);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            String principal = authentication == null ? "DEVELOPMENT" : authentication.getName();
            db.update("insert into governance_policy_versions(policy_id,revision,change_kind,restored_from_revision,snapshot_json,snapshot_digest,principal_digest,recorded_at) values(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
                    id, revision, kind, source, snapshot, hash(snapshot), hash(principal));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Policy snapshot serialization failed", e); }
    }

    private PolicyVersion version(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        try {
            String snapshot = row.getString("snapshot_json"), digest = row.getString("snapshot_digest");
            if (!hash(snapshot).equals(digest)) throw new IllegalStateException("Policy snapshot digest mismatch");
            return new PolicyVersion(row.getLong("revision"), row.getString("change_kind"), (Long)row.getObject("restored_from_revision"),
                    digest, row.getString("principal_digest"), row.getTimestamp("recorded_at").toInstant(),
                    objectMapper.readValue(snapshot, PolicyValues.class));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Policy snapshot decoding failed", e); }
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void apply(GovernancePolicy policy, GovernancePolicyDtos.PolicyRequest request) {
        if (request.name() == null || request.name().isBlank() || request.name().length() > 128
                || request.policyType() == null || request.enforcementMode() == null || request.scopeType() == null
                || !Set.of("GLOBAL", "AGENT", "ENDPOINT", "DATA_CATEGORY", "PROVIDER", "MODEL").contains(request.scopeType())
                || (request.scopeValue() != null && request.scopeValue().length() > 128)
                || (!"GLOBAL".equals(request.scopeType()) && (request.scopeValue() == null || request.scopeValue().isBlank()))
                || request.ruleJson() == null || request.ruleJson().length() > 16384)
            throw new BusinessException("治理策略名称、范围或规则超出允许格式");
        policy.setName(request.name());
        policy.setPolicyType(request.policyType());
        policy.setScopeType(request.scopeType());
        policy.setScopeValue(request.scopeValue());
        policy.setRuleJson(canonicalRuleJson(request.ruleJson()));
        policy.setEnforcementMode(request.enforcementMode());
        policy.setEnabled(request.enabled());
    }

    private void copy(GovernancePolicy source, GovernancePolicy target) {
        target.setName(source.getName()); target.setPolicyType(source.getPolicyType());
        target.setScopeType(source.getScopeType()); target.setScopeValue(source.getScopeValue());
        target.setRuleJson(source.getRuleJson()); target.setEnforcementMode(source.getEnforcementMode());
        target.setEnabled(source.isEnabled());
    }

    private String canonicalRuleJson(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.asText());
            if (node == null || !node.isObject()) {
                throw new BusinessException("治理策略规则必须是 JSON 对象");
            }
            return objectMapper.writeValueAsString(ordered(node));
        } catch (JsonProcessingException exception) {
            throw new BusinessException("治理策略规则不是有效 JSON");
        }
    }

    private GovernancePolicyDtos.PolicyResponse response(GovernancePolicy policy) {
        return new GovernancePolicyDtos.PolicyResponse(
                policy.getId(), policy.getName(), policy.getPolicyType(), policy.getScopeType(), policy.getScopeValue(),
                normalizedRuleJson(policy.getRuleJson()), policy.getEnforcementMode(), policy.isEnabled(),
                policy.getCreatedAt(), policy.getUpdatedAt(), policy.getRevision()
        );
    }

    private String normalizedRuleJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.asText());
            return node == null ? "{}" : objectMapper.writeValueAsString(ordered(node));
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }

    private JsonNode ordered(JsonNode node) {
        if (node.isObject()) {
            var result = objectMapper.createObjectNode();
            var names = new java.util.TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, ordered(node.get(name)))); return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode(); node.forEach(value -> result.add(ordered(value))); return result;
        }
        return node;
    }
}
