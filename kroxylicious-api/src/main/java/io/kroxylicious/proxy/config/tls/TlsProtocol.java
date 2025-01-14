/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import java.util.Arrays;
import java.util.Optional;

public enum TlsProtocol {
    SSL_V_2("SSLv2Hello"),
    SSL_V_3("SSLv3"),
    TLS_V_1("TLSv1"),
    TLS_V_1_1("TLSv1.1"),
    TLS_V_1_2("TLSv1.2"),
    TLS_V_1_3("TLSv1.3");

    String tlsProtocol;

    TlsProtocol(String tlsProtocol) {
        this.tlsProtocol = tlsProtocol;
    }

    public String getTlsProtocol() {
        return tlsProtocol;
    }

    public static Optional<TlsProtocol> getProtocolName(String tlsProtocol) {
        return Arrays.stream(TlsProtocol.values())
                .filter(protocol -> protocol.getTlsProtocol().equals(tlsProtocol))
                .findFirst();
    }
}
