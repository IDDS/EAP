package com.eap.platform.ax;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface AxTaskRepository extends JpaRepository<AxTask,Long> {
 String IDENTITY_QUERY="select new com.eap.platform.ax.AxTaskIdentity(t.agentId,t.taskKey,t.instanceKey,t.identityMode,t.identityScope,t.actorUid,t.actorTemplate,t.deliveryRequest,t.identityPublicKey,t.identityUntil,t.identityRevoked) from AxTask t";
 @Query(IDENTITY_QUERY+" where t.taskKey=:key and t.identityMode='PER_INSTANCE_V1'")
 Optional<AxTaskIdentity> identityByTaskKey(@org.springframework.data.repository.query.Param("key") String key);
 @Query(IDENTITY_QUERY+" where t.instanceKey=:key and t.identityMode='PER_INSTANCE_V1'")
 Optional<AxTaskIdentity> identityByInstanceKey(@org.springframework.data.repository.query.Param("key") String key);

 Optional<AxTask> findBySubmissionKey(String key);
 Optional<AxTask> findByTaskKey(String key);
 List<AxTask> findTop100ByOrderByIdDesc();
 List<AxTask> findTop20BySuspendedFalseOrderByUpdatedAtAsc();
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select t from AxTask t where t.id = :id")
 Optional<AxTask> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
