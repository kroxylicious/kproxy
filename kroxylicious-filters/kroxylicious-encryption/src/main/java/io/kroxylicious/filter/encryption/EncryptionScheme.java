/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption;

import java.util.Objects;
import java.util.Set;

import io.kroxylicious.kms.service.KekId;

/**
 * Describes how a record should be encrypted
 * @param kekId The KEK identifier to be used. Not null.
 * @param recordFields The fields of the record that should be encrypted with the given KEK. Neither null nor empty.
 * @param <K> The type of KEK identifier.
 */
public record EncryptionScheme<K>(
                                  KekId<K> kekId,
                                  Set<RecordField> recordFields) {
    public EncryptionScheme {
        Objects.requireNonNull(kekId);
        if (Objects.requireNonNull(recordFields).isEmpty()) {
            throw new IllegalArgumentException();
        }
    }

}