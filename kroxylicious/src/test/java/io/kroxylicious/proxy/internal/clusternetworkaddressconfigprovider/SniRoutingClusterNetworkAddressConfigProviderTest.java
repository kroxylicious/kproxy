/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.clusternetworkaddressconfigprovider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kroxylicious.proxy.service.HostPort;

import static io.kroxylicious.proxy.internal.clusternetworkaddressconfigprovider.SniRoutingClusterNetworkAddressConfigProvider.SniRoutingClusterNetworkAddressConfigProviderConfig;
import static io.kroxylicious.proxy.service.HostPort.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SniRoutingClusterNetworkAddressConfigProviderTest {

    @Test
    void valid() {
        new SniRoutingClusterNetworkAddressConfigProviderConfig(
                parse("good:1235"), "broker$(nodeId)-good");

    }

    @ParameterizedTest
    @ValueSource(strings = { "notgood", "toomany$(nodeId)$(nodeId)", "toomanyrecursive$(nodeId$(nodeId))", "shouty$(NODEID)badtoo" })
    @NullAndEmptySource
    void invalidBrokerPattern(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> new SniRoutingClusterNetworkAddressConfigProviderConfig(parse("good:1235"), input));
    }

    @Test
    void getBrokerAddress() {
        var provider = new SniRoutingClusterNetworkAddressConfigProvider(
                new SniRoutingClusterNetworkAddressConfigProviderConfig(parse("boot.kafka:1234"),
                        "broker-$(nodeId).kafka"));
        assertThat(provider.getBrokerAddress(0)).isEqualTo(HostPort.parse("broker-0.kafka:1234"));
    }

    @Test
    void badNodeId() {
        assertThrows(IllegalArgumentException.class, () -> new SniRoutingClusterNetworkAddressConfigProvider(
                new SniRoutingClusterNetworkAddressConfigProviderConfig(parse("boot.kafka:1234"), "broker-$(nodeId).kafka"))
                .getBrokerAddress(-1));
    }
}