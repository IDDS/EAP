package com.eap.platform.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessContentRecordRepository extends JpaRepository<BusinessContentRecord, Long> {
    Optional<BusinessContentRecord> findByObservationKey(String observationKey);

    @org.springframework.data.jpa.repository.Query("""
            select r.id from BusinessContentRecord r
            where (r.rawRetained = true or r.encryptedContent is not null or r.encryptionIv is not null)
              and (r.expiresAt <= :now or r.createdAt <= :receivedBefore)
            order by r.id
            """)
    java.util.List<Long> findExpiredRawIds(java.time.Instant now, java.time.Instant receivedBefore,
                                         org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.Query("select r.id from BusinessContentRecord r where r.direction = 'OUTPUT' and r.projectionStatus = 'PENDING' order by r.id")
    java.util.List<Long> findPendingProjectionIds(org.springframework.data.domain.Pageable page);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from BusinessContentRecord r where r.id = :id")
    Optional<BusinessContentRecord> lockForProjection(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query("select r.projectionStatus as status, count(r) as total from BusinessContentRecord r where r.direction = 'OUTPUT' group by r.projectionStatus")
    java.util.List<ProjectionCount> projectionCounts();

    interface ProjectionCount {
        String getStatus();
        long getTotal();
    }
}
