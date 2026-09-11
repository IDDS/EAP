package com.eap.platform.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ToolInvocationEventRepository extends JpaRepository<ToolInvocationEvent, Long>, JpaSpecificationExecutor<ToolInvocationEvent> {
    Optional<ToolInvocationEvent> findByInvocationKey(String invocationKey);

    List<ToolInvocationEvent> findTop500ByOrderByObservedAtDesc();
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id), 0) from ToolInvocationEvent e")
    long scanUpperBound();

    List<ToolInvocationEvent> findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            long after, long upper, org.springframework.data.domain.Pageable page);

}
