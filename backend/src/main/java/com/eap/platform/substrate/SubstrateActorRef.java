package com.eap.platform.substrate;

import java.util.regex.Pattern;

/**
 * An actor address stored in a runtime instance endpoint as {@code substrate://<atespace>/<actor>}.
 * The router selects the actor from the {@code ate-target-actor} header, never from the URL.
 */
public record SubstrateActorRef(String atespace, String name) {
    public static final String PROTOCOL = "SUBSTRATE";
    public static final String OBSERVED_PROTOCOL = "SUBSTRATE_OBSERVED";
    public static final String ROUTING_HEADER = "ate-target-actor";
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    public static SubstrateActorRef parse(String endpoint) {
        if (endpoint == null || !endpoint.startsWith("substrate://")) {
            throw new IllegalArgumentException("Substrate Runtime 地址必须是 substrate://<atespace>/<actor>");
        }
        String[] parts = endpoint.substring("substrate://".length()).split("/", -1);
        if (parts.length != 2 || !SEGMENT.matcher(parts[0]).matches() || !SEGMENT.matcher(parts[1]).matches()) {
            throw new IllegalArgumentException("Substrate Runtime 地址必须是 substrate://<atespace>/<actor>");
        }
        return new SubstrateActorRef(parts[0], parts[1]);
    }

    public static boolean isSubstrate(String protocol) {
        return PROTOCOL.equalsIgnoreCase(protocol) || OBSERVED_PROTOCOL.equalsIgnoreCase(protocol);
    }

    public String routingValue() {
        return atespace + "/" + name;
    }
}
