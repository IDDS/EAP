package com.eap.platform.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/** Bounded cyclic metadata scan. Every operation and its cursor advance commit together. */
@Service
public class ObservationAlertReconciliationService {
    private static final String PRINCIPAL = "system:observation-alert-reconciliation:v1";
    private final JdbcTemplate db;
    private final ObservationCoverageService coverage;
    private final ObservationAlertService alerts;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final long maxRunMs;

    public ObservationAlertReconciliationService(JdbcTemplate db, ObservationCoverageService coverage,
            ObservationAlertService alerts, PlatformTransactionManager transactions,
            @Value("${eap.observability.alert-reconciliation.batch-size:50}") int batchSize,
            @Value("${eap.observability.alert-reconciliation.max-run-ms:10000}") long maxRunMs) {
        if (batchSize < 1 || batchSize > 100 || maxRunMs < 100 || maxRunMs > 30000)
            throw new IllegalArgumentException("Invalid bounded observation alert scan configuration");
        this.db=db; this.coverage=coverage; this.alerts=alerts; this.batchSize=batchSize; this.maxRunMs=maxRunMs;
        tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(5);
    }
    public record Batch(long sweep, int processed, int failures, boolean sweepComplete) {}
    private record Step(boolean complete, boolean processed) {}
    static final class OperationFailure extends RuntimeException {
        final long sweep, revision, operation;
        OperationFailure(long sweep, long revision, long operation, RuntimeException cause) {
            super("Observation alert reconciliation failed", cause);
            this.sweep=sweep; this.revision=revision; this.operation=operation;
        }
    }
    private Map<String,Object> locked() {
        return db.queryForMap("select * from observation_alert_scan_cursor where id=1 for update");
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }

    /** The upper ID stays fixed until all visible originals in this sweep have been considered. */
    private long begin() {
        return tx.execute(status -> {
            var row=locked(); long sweep=number(row,"sweep_number");
            if (number(row,"last_operation_id") < number(row,"upper_operation_id")) return sweep;
            Long upper=coverage.highestVisibleOperation();
            if (upper==null) return sweep;
            sweep=Math.addExact(sweep,1);
            db.update("update observation_alert_scan_cursor set sweep_number=?,upper_operation_id=?,last_operation_id=0,cursor_revision=cursor_revision+1,changed_at=CURRENT_TIMESTAMP(6) where id=1",sweep,upper);
            return sweep;
        });
    }
    private Step next(long sweep) {
        return tx.execute(status -> {
            var row=locked();
            if (number(row,"sweep_number")!=sweep || number(row,"last_operation_id")>=number(row,"upper_operation_id"))
                return new Step(true,false);
            long upper=number(row,"upper_operation_id");
            Long operation=coverage.nextVisibleOperation(number(row,"last_operation_id"),upper);
            if (operation==null) {
                db.update("update observation_alert_scan_cursor set last_operation_id=upper_operation_id,cursor_revision=cursor_revision+1,changed_at=CURRENT_TIMESTAMP(6) where id=1");
                return new Step(true,false);
            }
            try {
                alerts.reconcile(operation,PRINCIPAL);
                db.update("update observation_alert_scan_cursor set last_operation_id=?,cursor_revision=cursor_revision+1,processed_count=processed_count+1,changed_at=CURRENT_TIMESTAMP(6) where id=1",operation);
                return new Step(operation==upper,true);
            } catch (RuntimeException failure) {
                // The entire operation transaction rolls back, including any partially created audit.
                throw new OperationFailure(sweep,number(row,"cursor_revision"),operation,failure);
            }
        });
    }
    private boolean skip(OperationFailure failure) {
        return tx.execute(status -> {
            var row=locked();
            // Another replica may already have committed this position after our rollback.
            if (number(row,"sweep_number")!=failure.sweep || number(row,"cursor_revision")!=failure.revision) return false;
            return db.update("update observation_alert_scan_cursor set last_operation_id=?,cursor_revision=cursor_revision+1,failure_count=failure_count+1,last_failure_operation_id=?,last_failure_code='RECONCILIATION_FAILED',last_failure_at=CURRENT_TIMESTAMP(6),changed_at=CURRENT_TIMESTAMP(6) where id=1",failure.operation,failure.operation)==1;
        });
    }
    public Batch runBatch() {
        long deadline=System.nanoTime()+maxRunMs*1_000_000L;
        long sweep=begin(); int processed=0, failures=0;
        for (int attempts=0; attempts<batchSize && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted(); attempts++) {
            try {
                var step=next(sweep); if(step.processed()) processed++;
                if(step.complete()) return new Batch(sweep,processed,failures,true);
            } catch (OperationFailure failure) {
                if(skip(failure)) failures++;
            }
        }
        return new Batch(sweep,processed,failures,false);
    }
}
