package com.eap.platform.ax;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
@Entity @Table(name="ax_tasks")
public class AxTask {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 @Column(name="task_key",nullable=false,length=64) String taskKey;
 @Column(name="submission_key",nullable=false,length=64) String submissionKey;
 @Column(name="workflow_id") Long workflowId;
 @Column(name="workflow_run_key",length=64) String workflowRunKey;
 @Column(name="workflow_node_key",length=64) String workflowNodeKey;
 @Column(name="agent_id",nullable=false) Long agentId;
 @Column(nullable=false,length=64) String fingerprint;
 @Column(name="requested_by",nullable=false,length=128) String requestedBy;
 @Column(nullable=false,length=32) String status;
 @Column(nullable=false,length=32) String phase;
 @Column(nullable=false,length=256) String reason;
 @Column(name="payload_json",nullable=false,columnDefinition="LONGTEXT") String payloadJson;
 @Column(name="result_json",columnDefinition="LONGTEXT") String resultJson;
 @Column(name="created_at",nullable=false) Instant createdAt;
 @Column(name="updated_at",nullable=false) Instant updatedAt;
 @Column(name="deadline_at",nullable=false) Instant deadlineAt;
 @Column(name="lease_until") Instant leaseUntil;
 @Column(name="auto_suspend",nullable=false) boolean autoSuspend=true;
 @Column(nullable=false) boolean suspended;
 @Column(name="identity_mode",nullable=false,length=24) String identityMode="LEGACY_SHARED";
 @Column(name="identity_scope",length=63) String identityScope;
 @Column(name="runner_image",length=768) String runnerImage;
 @Column(name="identity_public_key",length=256) String identityPublicKey;
 @Column(name="identity_gateway_url",length=2048) String identityGatewayUrl;
 @Column(name="identity_gateway_host",length=253) String identityGatewayHost;
 @Column(name="delivery_request",length=36) String deliveryRequest;
 @Column(name="instance_key",length=128) String instanceKey;
 @Column(name="actor_uid",length=64) String actorUid;
 @Column(name="actor_template",length=128) String actorTemplate;
 @Column(name="identity_until") Instant identityUntil;
 @Column(name="identity_revoked",nullable=false) boolean identityRevoked;
 boolean scoped(){if(!Set.of("LEGACY_SHARED","PER_INSTANCE_V1").contains(identityMode))throw new IllegalStateException("任务身份模式无效");return "PER_INSTANCE_V1".equals(identityMode);}
}
