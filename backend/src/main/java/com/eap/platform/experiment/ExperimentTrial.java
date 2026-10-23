package com.eap.platform.experiment;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="agent_experiment_trials")
public class ExperimentTrial {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 @Column(name="experiment_id",nullable=false) Long experimentId;
 @Column(name="variant_id",nullable=false) Long variantId;
 @Column(name="sample_index",nullable=false) Integer sampleIndex;
 @Column(nullable=false,length=32) String status="QUEUED";
 @Column(name="task_key",nullable=false,length=96) String taskKey;
 @Column(name="trace_id",nullable=false,length=32) String traceId;
 @Column(name="output_text",columnDefinition="text") String outputText;
 @Column(name="finish_reason",length=32) String finishReason;
 @Column(name="input_tokens") Long inputTokens;
 @Column(name="output_tokens") Long outputTokens;
 @Column(name="usage_source",nullable=false,length=32) String usageSource="UNKNOWN";
 @Column(name="latency_ms") Long latencyMs;
 @Column(name="end_to_end_ms") Long endToEndMs;
 @Column(name="queue_ms") Long queueMs;
 Integer score;
 @Column(name="reason_code",length=64) String reasonCode;
 @Column(name="started_at") Instant startedAt;
 @Column(name="finished_at") Instant finishedAt;
}
