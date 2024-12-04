/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.config.tls;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Options that control TLS negotiation when in server mode.
 */
public record ServerOptions(@Nullable TlsClientAuth clientAuth) implements TrustOptions {
    @Override
    public boolean forClient() {
        return false;
    }
}
