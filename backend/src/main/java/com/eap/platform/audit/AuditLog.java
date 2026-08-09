package com.eap.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, length = 128)
    String actor;

    @Column(nullable = false, length = 128)
    String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    String resourceType;

    @Column(name = "resource_id", length = 64)
    String resourceId;

    @Column(name = "before_json", columnDefinition = "json")
    String beforeJson;

    @Column(name = "after_json", columnDefinition = "json")
    String afterJson;

    @Column(nullable = false, length = 32)
    String result;

    @Column(name = "trace_id", nullable = false, length = 64)
    String traceId;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    public void setActor(String actor) {
        this.actor = actor;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
