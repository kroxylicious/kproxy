/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault;

import java.nio.ByteBuffer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kroxylicious.kms.service.KekId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultEdekSerdeTest {

    private final VaultEdekSerde serde = new VaultEdekSerde();

    static Stream<Arguments> keyRefs() {
        return Stream.of(
                Arguments.of("ordinary looking keyref", new StringKekid("mykey")),
                Arguments.of("short keyref", new StringKekid("k")),
                Arguments.of("outwith ascii", new StringKekid("k€yr€f")),
                Arguments.of("longer keyref, len just fits in single byte", new StringKekid("x".repeat(127))),
                Arguments.of("longer keyref, len requires multiple bytes", new StringKekid("x".repeat(128))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(value = "keyRefs")
    void shouldRoundTrip(String name, KekId keyRef) {
        var edek = new VaultEdek(keyRef, new byte[]{ 1, 2, 3 });
        var buf = ByteBuffer.allocate(serde.sizeOf(edek));
        serde.serialize(edek, buf);
        buf.flip();
        var deserialized = serde.deserialize(buf);
        assertThat(deserialized).isEqualTo(edek);
    }

    static Stream<Arguments> sizeOf() {
        return Stream.of(
                Arguments.of(
                        "ordinary",
                        new VaultEdek(new StringKekid("a"), new byte[]{ 1 }),
                        1 + 1 + 1),
                Arguments.of(
                        "longer keyref, len just fits in single byte",
                        new VaultEdek(new StringKekid("a".repeat(127)), new byte[]{ 1 }),
                        1 + 127 + 1),
                Arguments.of(
                        "longer keyref, len requires multiple bytes",
                        new VaultEdek(new StringKekid("a".repeat(128)), new byte[]{ 1 }),
                        2 + 128 + 1),
                Arguments.of(
                        "longer edek",
                        new VaultEdek(new StringKekid("abc"), new byte[]{ 1, 2, 3, 4 }),
                        1 + 3 + 4));
    }

    @ParameterizedTest
    @MethodSource
    void sizeOf(String name, VaultEdek edek, int expectedSize) {
        var size = serde.sizeOf(edek);
        assertThat(size).isEqualTo(expectedSize);
    }

    static Stream<Arguments> deserializeErrors() {
        return Stream.of(
                Arguments.of("emptykek", new byte[]{ 0 }),
                Arguments.of("noekekbytes", new byte[]{ 3, 'A', 'B', 'C' }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void deserializeErrors(String name, byte[] serializedBytes) {
        var buf = ByteBuffer.wrap(serializedBytes);
        assertThatThrownBy(() -> serde.deserialize(buf))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
