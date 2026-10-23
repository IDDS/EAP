package com.eap.platform.experiment;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="agent_experiments")
public class AgentExperiment {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 @Column(name="agent_id",nullable=false) Long agentId;
 @Column(name="optimization_run_id") Long optimizationRunId;
 @Column(name="instance_key",nullable=false,length=128) String instanceKey;
 @Column(name="actor_uid",nullable=false,length=64) String actorUid;
 @Column(nullable=false,length=128) String template;
 @Column(nullable=false,length=512) String endpoint;
 @Column(name="image_ref",nullable=false,length=512) String imageRef;
 @Column(name="deployment_version",nullable=false) Integer deploymentVersion;
 @Column(name="submission_key",nullable=false,length=64) String submissionKey;
 @Column(name="request_fingerprint",nullable=false,length=64) String requestFingerprint;
 @Column(name="profile_digest",nullable=false,length=64) String profileDigest;
 @Column(name="profile_json",nullable=false,columnDefinition="text") String profileJson;
 @Column(name="samples_json",nullable=false,columnDefinition="text") String samplesJson;
 @Column(name="max_tokens",nullable=false) Integer maxTokens;
 @Column(name="requested_by",nullable=false,length=128) String requestedBy;
 @Column(nullable=false,length=32) String status="QUEUED";
 @Column(name="created_at",nullable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 @PrePersist void create(){createdAt=Instant.now();updatedAt=createdAt;}
 @PreUpdate void update(){updatedAt=Instant.now();}
}
