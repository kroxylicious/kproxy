/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

record CreatePolicy(String policy) {

    static CreatePolicy fromInputStream(InputStream is) {
        Objects.requireNonNull(is);
        try {
            return new CreatePolicy(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Failed to create a CreatePolicy", e);
        }
    }
}
