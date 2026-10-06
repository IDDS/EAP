package com.eap.platform.experiment;
import jakarta.persistence.*;
@Entity @Table(name="agent_experiment_variants")
public class ExperimentVariant {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
 @Column(name="experiment_id",nullable=false) Long experimentId;
 @Column(name="variant_name",nullable=false,length=16) String variantName;
 @Column(name="config_digest",nullable=false,length=64) String configDigest;
 @Column(name="system_prompt",nullable=false,columnDefinition="text") String systemPrompt;
}
