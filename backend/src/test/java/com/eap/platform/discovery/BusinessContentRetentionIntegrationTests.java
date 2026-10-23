package com.eap.platform.discovery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles("test")
@Transactional
class BusinessContentRetentionIntegrationTests {
    @Autowired BusinessContentRecordRepository records;
    @Autowired BusinessContentRetentionService retention;
    @Autowired ContentAnalysisProjectionService projection;
    @Autowired EntityManager entityManager;

    @Test void erasesOnlyExpiredRawMaterialAndPreservesEvidence() {
        var expired = record(Instant.now().minusSeconds(10));
        var future = record(Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(retention.eraseExpired(100)).isEqualTo(1);
        reload();
        var erased = records.findById(expired.id).orElseThrow();
        assertThat(erased.encryptedContent).isNull();
        assertThat(erased.encryptionIv).isNull();
        assertThat(erased.encryptionKeyVersion).isNull();
        assertThat(erased.rawRetained).isFalse();
        assertThat(erased.rawErasedAt).isNotNull();
        assertThat(erased.contentHash).isEqualTo("a".repeat(64));
        assertThat(erased.redactedContent).isEqualTo("redacted fixture");
        assertThat(erased.observationKey).isEqualTo(expired.observationKey);
        assertThat(erased.traceId).isEqualTo("retention-trace");
        assertThat(records.findById(future.id).orElseThrow().encryptedContent).isEqualTo("synthetic ciphertext");
        Instant erasedAt = erased.rawErasedAt;
        assertThat(retention.eraseExpired(100)).isZero();
        reload();
        assertThat(records.findById(expired.id).orElseThrow().rawErasedAt).isEqualTo(erasedAt);
    }

    @Test void resumesBoundedBatchesWithoutSkippingRows() {
        for (int i = 0; i < 5; i++) record(Instant.now().minusSeconds(5));
        assertThat(retention.eraseExpired(2)).isEqualTo(2); reload();
        assertThat(retention.eraseExpired(2)).isEqualTo(2); reload();
        assertThat(retention.eraseExpired(2)).isEqualTo(1); reload();
        assertThat(retention.eraseExpired(2)).isZero();
    }

    @Test void legacyFutureOrMissingExpiryCannotExtendServerRetention() {
        var future = record(Instant.now().plus(300, ChronoUnit.DAYS));
        var missing = record(null);
        var inconsistent = record(null);
        inconsistent.rawRetained = false;
        for (var row : java.util.List.of(future, missing, inconsistent)) {
            row.createdAt = Instant.now().minus(31, ChronoUnit.DAYS);
            row.observedAt = Instant.now().plus(300, ChronoUnit.DAYS);
        }
        reload();
        assertThat(retention.eraseExpired(100)).isEqualTo(3);
        reload();
        for (var row : java.util.List.of(future, missing, inconsistent)) {
            var saved = records.findById(row.id).orElseThrow();
            assertThat(saved.encryptedContent).isNull();
            assertThat(saved.expiresAt).isBefore(Instant.now());
        }
    }

    @Test void projectionAfterErasureCannotRestoreRawMaterial() {
        var row = record(Instant.now().minusSeconds(5));
        row.direction = "OUTPUT";
        row.traceId = null;
        reload();
        assertThat(retention.eraseExpired(10)).isEqualTo(1); reload();
        projection.project(row.id); reload();
        var saved = records.findById(row.id).orElseThrow();
        assertThat(saved.projectionStatus).isEqualTo("SKIPPED");
        assertThat(saved.encryptedContent).isNull();
        assertThat(saved.rawRetained).isFalse();
    }

    private void reload() { entityManager.flush(); entityManager.clear(); }
    private BusinessContentRecord record(Instant expiresAt) {
        var row = new BusinessContentRecord();
        row.observationKey = UUID.randomUUID().toString(); row.tenantId = "retention-fixture";
        row.endpointKey = "retention-endpoint"; row.direction = "INPUT"; row.traceId = "retention-trace";
        row.contentHash = "a".repeat(64); row.contentBytes = 17L;
        row.redactedContent = "redacted fixture"; row.encryptedContent = "synthetic ciphertext";
        row.encryptionIv = "synthetic iv"; row.encryptionKeyVersion = "fixture-v1";
        row.rawRetained = true; row.sampled = true; row.expiresAt = expiresAt;
        return records.saveAndFlush(row);
    }
}
