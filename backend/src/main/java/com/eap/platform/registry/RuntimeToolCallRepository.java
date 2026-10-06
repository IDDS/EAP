package com.eap.platform.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RuntimeToolCallRepository extends JpaRepository<RuntimeToolCall,Long> {
    Optional<RuntimeToolCall> findByInstanceKeyAndCallId(String instanceKey,String callId);
    List<RuntimeToolCall> findTop30ByInstanceKeyOrderByCreatedAtDescIdDesc(String instanceKey);
}
