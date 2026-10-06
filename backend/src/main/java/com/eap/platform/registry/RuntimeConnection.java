package com.eap.platform.registry;

import jakarta.persistence.*;
import java.time.Instant;

/** Only non-secret generation and evidence metadata are persisted. */
@Entity
@Table(name="runtime_connections")
public class RuntimeConnection {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Version long revision;
    @Column(name="instance_key",nullable=false,unique=true,length=128) String instanceKey;
    @Column(name="request_key",length=36) String requestKey;
    @Column(name="generation",length=36) String generation;
    @Column(name="expires_at") Instant expiresAt;
    @Column(name="verified_at") Instant verifiedAt;
    @Column(name="gateway_at") Instant gatewayAt;
    @Column(name="gateway_trace",length=128) String gatewayTrace;
    @Column(name="last_message",length=200) String lastMessage;
    @Column(name="trace_at") Instant traceAt;
    @Column(name="identity_expires_at") Instant identityExpiresAt;
    @Column(name="credential_serial",nullable=false) long credentialSerial;
    @Column(name="delivery_serial") Long deliverySerial;
    // Credential leases rotate without replacing the identity epoch used by grants and operations.
    @Column(name="credential_revision",length=36) String credentialRevision;
    @Column(name="automatic_delivery",nullable=false) boolean automaticDelivery;
    @Column(name="delivery_operation",length=36) String deliveryOperation;
    @Column(name="delivery_revision",length=36) String deliveryRevision;
    @Column(name="delivery_expires_at") Instant deliveryExpiresAt;
    @Column(name="last_delivery_at") Instant lastDeliveryAt;
}
