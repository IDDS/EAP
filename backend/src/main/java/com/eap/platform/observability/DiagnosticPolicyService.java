package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

@Service
public class DiagnosticPolicyService {
    public record Policy(long version, String algorithm, int sampleBasisPoints, Instant createdAt) {}
    private final JdbcTemplate db;

    public DiagnosticPolicyService(JdbcTemplate db) { this.db = db; }

    @Transactional(readOnly = true)
    public Policy current() {
        return db.queryForObject("select v.* from diagnostic_policy_head h join diagnostic_policy_versions v on v.version=h.current_version where h.id=1",
                (r, n) -> new Policy(r.getLong("version"), r.getString("algorithm"), r.getInt("sample_basis_points"), r.getTimestamp("created_at").toInstant()));
    }

    /** Serialize publication and new registrations, while leaving published versions immutable. */
    @Transactional
    public Policy freezeCurrent() {
        long version = db.queryForObject("select current_version from diagnostic_policy_head where id=1 for update", Long.class);
        // A locking read avoids an earlier caller snapshot hiding a newly published version on MySQL.
        return db.queryForObject("select * from diagnostic_policy_versions where version=? for update",
                (r, n) -> new Policy(r.getLong("version"), r.getString("algorithm"), r.getInt("sample_basis_points"), r.getTimestamp("created_at").toInstant()), version);
    }

    @Transactional
    public Policy publish(JsonNode request, String principal) {
        if (request == null || !request.isObject() || request.size() != 2
                || !Set.of("expectedVersion", "sampleBasisPoints").containsAll(properties(request))
                || !request.path("expectedVersion").isIntegralNumber() || !request.path("expectedVersion").canConvertToLong()
                || !request.path("sampleBasisPoints").isIntegralNumber() || !request.path("sampleBasisPoints").canConvertToInt()) {
            throw new BusinessException("仅接受整数 expectedVersion 和 sampleBasisPoints");
        }
        long expected = request.path("expectedVersion").longValue();
        int rate = request.path("sampleBasisPoints").intValue();
        if (expected < 1 || rate < 0 || rate > 10000) throw new BusinessException("策略版本或采样比例超出范围");
        Policy original = freezeCurrent();
        if (expected != original.version()) throw new ConflictException("诊断策略已更新，请重新读取版本");
        db.update("insert into diagnostic_policy_versions(version,algorithm,sample_basis_points,principal_digest,created_at) values(?,'SHA256_TRACE_V1',?,?,CURRENT_TIMESTAMP)",
                expected + 1, rate, hash(principal == null ? "console" : principal));
        db.update("update diagnostic_policy_head set current_version=? where id=1", expected + 1);
        return current();
    }

    private Set<String> properties(JsonNode value) {
        var result = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
