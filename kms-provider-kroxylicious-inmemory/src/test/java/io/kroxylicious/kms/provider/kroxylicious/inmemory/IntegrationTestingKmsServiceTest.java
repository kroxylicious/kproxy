/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.kroxylicious.inmemory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kroxylicious.kms.service.Serde;
import io.kroxylicious.kms.service.UnknownAliasException;
import io.kroxylicious.kms.service.UnknownKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationTestingKmsServiceTest {

    IntegrationTestingKmsService service;

    @BeforeEach
    public void before() {
        service = IntegrationTestingKmsService.newInstance();
    }

    @Test
    void shouldRejectNullName() {
        // given
        assertThrows(IllegalArgumentException.class, () -> new IntegrationTestingKmsService.Config(null));
    }

    @Test
    void shouldRejectEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationTestingKmsService.Config(""));
    }

    @Test
    void shouldWorkAcrossServiceInstances() {
        // given
        var kmsId = UUID.randomUUID().toString();
        var kms = service.buildKms(new IntegrationTestingKmsService.Config(kmsId));
        var kek = kms.generateKey();
        assertNotNull(kek);
        kms.createAlias(kek, "myAlias");

        // when
        var theSameKms = IntegrationTestingKmsService.newInstance().buildKms(new IntegrationTestingKmsService.Config(kmsId));

        // then
        assertEquals(kek, theSameKms.resolveAlias("myAlias").join());

        IntegrationTestingKmsService.delete(kmsId);
    }

    @Test
    void shouldSerializeAndDeserialiseKeks() {
        // given
        var kmsId = UUID.randomUUID().toString();
        var kms = service.buildKms(new IntegrationTestingKmsService.Config(kmsId));
        var kek = kms.generateKey();
        assertNotNull(kek);

        // when
        Serde<UUID> keyIdSerde = kms.keyIdSerde();
        var buffer = ByteBuffer.allocate(keyIdSerde.sizeOf(kek));
        keyIdSerde.serialize(kek, buffer);
        assertFalse(buffer.hasRemaining());
        buffer.flip();
        var loadedKek = keyIdSerde.deserialize(buffer);

        // then
        assertEquals(kek, loadedKek, "Expect the deserialized kek to be equal to the original kek");
        IntegrationTestingKmsService.delete(kmsId);
    }

    @Test
    void shouldGenerateDeks() {
        // given
        var kms1Id = UUID.randomUUID().toString();
        var kms1 = service.buildKms(new IntegrationTestingKmsService.Config(kms1Id));
        var kms2Id = UUID.randomUUID().toString();
        var kms2 = IntegrationTestingKmsService.newInstance().buildKms(new IntegrationTestingKmsService.Config(kms2Id));
        var key1 = kms1.generateKey();
        assertNotNull(key1);
        var key2 = kms2.generateKey();
        assertNotNull(key2);

        // when
        CompletableFuture<InMemoryEdek> gen1 = kms1.generateDek(key1);
        CompletableFuture<InMemoryEdek> gen2 = kms2.generateDek(key2);

        // then
        assertThat(gen1).isCompleted();
        assertThat(gen2).isCompleted();

        IntegrationTestingKmsService.delete(kms1Id);
        IntegrationTestingKmsService.delete(kms2Id);
    }

    @Test
    void shouldRejectsAnotherKmsesKeks() {
        // given
        var kms1Id = UUID.randomUUID().toString();
        var kms1 = service.buildKms(new IntegrationTestingKmsService.Config(kms1Id));
        var kms2Id = UUID.randomUUID().toString();
        var kms2 = IntegrationTestingKmsService.newInstance().buildKms(new IntegrationTestingKmsService.Config(kms2Id));
        var key1 = kms1.generateKey();
        assertNotNull(key1);
        var key2 = kms2.generateKey();
        assertNotNull(key2);

        // when
        CompletableFuture<InMemoryEdek> gen1 = kms1.generateDek(key2);
        CompletableFuture<InMemoryEdek> gen2 = kms2.generateDek(key1);

        // then
        assertThat(gen1).failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(UnknownKeyException.class)
                .withMessage("io.kroxylicious.kms.service.UnknownKeyException");

        assertThat(gen2).failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(UnknownKeyException.class)
                .withMessage("io.kroxylicious.kms.service.UnknownKeyException");

        IntegrationTestingKmsService.delete(kms1Id);
        IntegrationTestingKmsService.delete(kms2Id);
    }

    @Test
    void shouldDecryptDeks() {
        // given
        var kmsId = UUID.randomUUID().toString();
        var kms = service.buildKms(new IntegrationTestingKmsService.Config(kmsId));
        var kek = kms.generateKey();
        assertNotNull(kek);
        var pair = kms.generateDekPair(kek).join();
        assertNotNull(pair);
        assertNotNull(pair.edek());
        assertNotNull(pair.dek());

        // when
        var decryptedDek = kms.decryptEdek(kek, pair.edek()).join();

        // then
        assertEquals(pair.dek(), decryptedDek, "Expect the decrypted DEK to equal the originally generated DEK");

        IntegrationTestingKmsService.delete(kmsId);
    }

    @Test
    void shouldSerializeAndDeserializeEdeks() {
        var kmsId = UUID.randomUUID().toString();
        var kms = service.buildKms(new IntegrationTestingKmsService.Config(kmsId));
        var kek = kms.generateKey();

        var edek = kms.generateDek(kek).join();

        var serde = kms.edekSerde();
        var buffer = ByteBuffer.allocate(serde.sizeOf(edek));
        serde.serialize(edek, buffer);
        assertFalse(buffer.hasRemaining());
        buffer.flip();

        var deserialized = serde.deserialize(buffer);

        assertEquals(edek, deserialized);

        IntegrationTestingKmsService.delete(kmsId);
    }

    @Test
    void shouldLookupByAlias() {
        var kmsId = UUID.randomUUID().toString();
        var kms = service.buildKms(new IntegrationTestingKmsService.Config(kmsId));
        var kek = kms.generateKey();

        var lookup = kms.resolveAlias("bob");
        assertThat(lookup).failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(UnknownAliasException.class)
                .withMessage("io.kroxylicious.kms.service.UnknownAliasException: bob");

        kms.createAlias(kek, "bob");
        var gotFromAlias = kms.resolveAlias("bob").join();
        assertEquals(kek, gotFromAlias);

        kms.deleteAlias("bob");
        lookup = kms.resolveAlias("bob");
        assertThat(lookup).failsWithin(Duration.ZERO)
                .withThrowableOfType(ExecutionException.class)
                .withCauseInstanceOf(UnknownAliasException.class)
                .withMessage("io.kroxylicious.kms.service.UnknownAliasException: bob");

        IntegrationTestingKmsService.delete(kmsId);
    }

}
