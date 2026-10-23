package com.eap.platform.discovery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Each bounded batch commits erasure and its timestamp together. No raw content is logged. */
@Service
public class BusinessContentRetentionService {
    private final BusinessContentRecordRepository repository;
    private final DiscoveryAgentV1Properties properties;

    public BusinessContentRetentionService(BusinessContentRecordRepository repository,
                                           DiscoveryAgentV1Properties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(timeout = 30)
    public int eraseExpired(int batchSize) {
        Instant now = Instant.now();
        int days = properties.getRawContentRetentionDays();
        int erased = 0;
        for (Long id : repository.findExpiredRawIds(now, now.minus(days, ChronoUnit.DAYS),
                PageRequest.of(0, Math.max(1, Math.min(batchSize, 500))))) {
            // Share the row lock with analysis, so a stale projection cannot restore ciphertext.
            BusinessContentRecord record = repository.lockForProjection(id).orElse(null);
            if (record == null || !Boolean.TRUE.equals(record.rawRetained)
                    && record.encryptedContent == null && record.encryptionIv == null) continue;
            Instant deadline = record.createdAt.plus(days, ChronoUnit.DAYS);
            if (record.expiresAt != null && record.expiresAt.isBefore(deadline)) deadline = record.expiresAt;
            if (deadline.isAfter(now)) continue;
            record.encryptedContent = null;
            record.encryptionIv = null;
            record.encryptionKeyVersion = null;
            record.rawRetained = false;
            record.expiresAt = deadline;
            record.rawErasedAt = now;
            erased++;
        }
        return erased;
    }
}
