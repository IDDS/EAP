package com.eap.platform.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ToolInvocationEventRepository extends JpaRepository<ToolInvocationEvent, Long>, JpaSpecificationExecutor<ToolInvocationEvent> {
    Optional<ToolInvocationEvent> findByInvocationKey(String invocationKey);

    List<ToolInvocationEvent> findTop500ByOrderByObservedAtDesc();
}
