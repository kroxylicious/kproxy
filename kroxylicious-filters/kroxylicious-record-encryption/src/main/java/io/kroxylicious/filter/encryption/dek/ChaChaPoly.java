/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.dek;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.function.Supplier;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;

public class ChaChaPoly implements CipherManager {
    private static final int NONCE_SIZE_BYTES = 12;

    public static ChaChaPoly INSTANCE = new ChaChaPoly();

    private ChaChaPoly() {
    }

    @Override
    public long maxEncryptionsPerKey() {
        return Long.MAX_VALUE; // 2^96 would be necessary given we use Wrapping96BitCounter
    }

    @Override
    public Cipher newCipher() {
        try {
            return Cipher.getInstance("ChaCha20-Poly1305");
        }
        catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            throw new DekException(e);
        }
    }

    @Override
    public Supplier<AlgorithmParameterSpec> paramSupplier() {
        // Per https://www.rfc-editor.org/rfc/rfc7539#section-4
        // we generate the nonce using a counter
        var generator = new Wrapping96BitCounter(new SecureRandom());
        var nonce = new byte[NONCE_SIZE_BYTES];
        return () -> {
            generator.generateIv(nonce);
            return new IvParameterSpec(nonce);
        };
    }

    @Override
    public int constantParamsSize() {
        return NONCE_SIZE_BYTES;
    }

    @Override
    public int size(AlgorithmParameterSpec parameterSpec) {
        return constantParamsSize();
    }

    @Override
    public void writeParameters(
                                ByteBuffer parametersBuffer,
                                AlgorithmParameterSpec params) {
        parametersBuffer.put(((IvParameterSpec) params).getIV());
    }

    @Override
    public AlgorithmParameterSpec readParameters(ByteBuffer parametersBuffer) {
        byte[] nonce = new byte[NONCE_SIZE_BYTES];
        parametersBuffer.get(nonce);
        return new IvParameterSpec(nonce);
    }
}
