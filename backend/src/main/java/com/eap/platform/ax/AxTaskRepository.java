package com.eap.platform.ax;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AxTaskRepository extends JpaRepository<AxTask,Long> {
 Optional<AxTask> findBySubmissionKey(String key);
 Optional<AxTask> findByTaskKey(String key);
 List<AxTask> findTop100ByOrderByIdDesc();
 List<AxTask> findTop20BySuspendedFalseOrderByUpdatedAtAsc();
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from AxTask t where t.id = :id")
 Optional<AxTask> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
