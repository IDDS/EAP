package com.eap.platform.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubstrateDeploymentRepository extends JpaRepository<SubstrateDeployment, Long> {
    java.util.Optional<SubstrateDeployment> findByDeploymentKey(String key);
    interface MonitorDeployment {
        Long getAgentId(); Integer getVersion(); String getTemplateName(); String getStatus();
    }
    @org.springframework.data.jpa.repository.Query("select d.agentId as agentId, d.version as version, d.templateName as templateName, d.status as status from SubstrateDeployment d where d.atespace = :atespace order by d.id desc")
    List<MonitorDeployment> monitorDeployments(@org.springframework.data.repository.query.Param("atespace") String atespace, org.springframework.data.domain.Pageable pageable);

    List<SubstrateDeployment> findTop20ByAgentIdOrderByVersionDesc(Long agentId);

    Optional<SubstrateDeployment> findFirstByAgentIdOrderByVersionDesc(Long agentId);

    Optional<SubstrateDeployment> findFirstByAgentIdAndStatusOrderByVersionDesc(Long agentId, String status);

    Optional<SubstrateDeployment> findByAgentIdAndVersion(Long agentId, Integer version);

    Optional<SubstrateDeployment> findByAgentIdAndSubmissionKey(Long agentId, String submissionKey);

    Optional<SubstrateDeployment> findFirstByStatusInOrderByUpdatedAtAscIdAsc(Collection<String> statuses);
}
