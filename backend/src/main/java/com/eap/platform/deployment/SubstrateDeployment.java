package com.eap.platform.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/** One console deployment of an agent version onto Agent Substrate. */
@Entity
@Table(name = "substrate_agent_deployments")
public class SubstrateDeployment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "deployment_key", nullable = false, length = 64)
    String deploymentKey;
    @Column(name = "agent_id", nullable = false)
    Long agentId;
    @Column(nullable = false)
    Integer version;
    @Column(name = "source_type", nullable = false, length = 16)
    String sourceType;
    @Column(nullable = false, length = 32)
    String status;
    @Column(name = "config_json", nullable = false, columnDefinition = "text")
    String configJson;
    @Column(name = "image_ref", length = 512)
    String imageRef;
    @Column(nullable = false, length = 63)
    String atespace;
    @Column(name = "template_name", nullable = false, length = 63)
    String templateName;
    @Column(name = "instance_key", length = 128)
    String instanceKey;
    @Column(name = "steps_json", nullable = false, columnDefinition = "text")
    String stepsJson = "[]";
    @Column(length = 512)
    String reason;
    @Column(name = "requested_by", nullable = false, length = 128)
    String requestedBy;
    @Column(name = "submission_key", length = 64)
    String submissionKey;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    public Long getAgentId() {return agentId;}
    public Integer getVersion() {return version;}
    public String getImageRef() {return imageRef;}
    public String getInstanceKey() {return instanceKey;}
    public String getTemplateName() {return templateName;}
    public String getAtespace() {return atespace;}
    public String getStatus() {return status;}
    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
