/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault.config;

public class SslConfigurationException extends RuntimeException {
    public SslConfigurationException(Exception cause) {
        super(cause);
    }

    public SslConfigurationException(String message) {
        super(message);
    }
}
