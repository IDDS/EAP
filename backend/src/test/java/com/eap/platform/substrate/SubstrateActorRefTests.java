package com.eap.platform.substrate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubstrateActorRefTests {
    @Test
    void parsesAtespaceAndActor() {
        var ref = SubstrateActorRef.parse("substrate://eap-demo/session-1");

        assertThat(ref.atespace()).isEqualTo("eap-demo");
        assertThat(ref.name()).isEqualTo("session-1");
        assertThat(ref.routingValue()).isEqualTo("eap-demo/session-1");
    }

    @Test
    void rejectsAddressesThatCouldSmuggleExtraRouting() {
        for (String endpoint : new String[]{"http://eap-demo/session-1", "substrate://eap-demo", "substrate://eap-demo/a/b",
                "substrate://EAP/Upper", "substrate://eap-demo/session-1?x=1", "substrate:///session-1"}) {
            assertThatThrownBy(() -> SubstrateActorRef.parse(endpoint)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
