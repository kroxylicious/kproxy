/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.inband;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.utils.ByteUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.kroxylicious.filter.encryption.AadSpec;
import io.kroxylicious.filter.encryption.CipherCode;
import io.kroxylicious.filter.encryption.EncryptionException;
import io.kroxylicious.filter.encryption.EncryptionScheme;
import io.kroxylicious.filter.encryption.EncryptionVersion;
import io.kroxylicious.filter.encryption.KeyManager;
import io.kroxylicious.filter.encryption.Receiver;
import io.kroxylicious.filter.encryption.RecordField;
import io.kroxylicious.filter.encryption.WrapperVersion;
import io.kroxylicious.kms.service.KekId;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.Serde;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * An implementation of {@link KeyManager} that uses envelope encryption, AES-GCM and stores the KEK id and encrypted DEK
 * alongside the record ("in-band").
 * @param <E> The type of the encrypted DEK.
 */
public class InBandKeyManager<E> implements KeyManager {

    private static final int MAX_ATTEMPTS = 3;

    /**
     * The encryption header. The value is the encryption version that was used to serialize the parcel and the wrapper.
     */
    static final String ENCRYPTION_HEADER_NAME = "kroxylicious.io/encryption";

    /**
     * The encryption version used on the produce path.
     * Note that the encryption version used on the fetch path is read from the
     * {@link #ENCRYPTION_HEADER_NAME} header.
     */
    private final EncryptionVersion encryptionVersion;

    private final Kms<E> kms;
    private final BufferPool bufferPool;
    private final Serde<E> edekSerde;
    // TODO cache expiry, with key descruction
    private final AsyncLoadingCache<KekId, KeyContext> keyContextCache;
    private final ConcurrentHashMap<E, CompletionStage<AesGcmEncryptor>> decryptorCache;
    private final long dekTtlNanos;
    private final int maxEncryptionsPerDek;
    private final Header[] encryptionHeader;
    private static final Logger LOGGER = LoggerFactory.getLogger(InBandKeyManager.class);

    public InBandKeyManager(Kms<E> kms,
                            BufferPool bufferPool,
                            int maxEncryptionsPerDek) {
        this.kms = kms;
        this.bufferPool = bufferPool;
        this.edekSerde = kms.edekSerde();
        this.dekTtlNanos = 5_000_000_000L;
        this.maxEncryptionsPerDek = maxEncryptionsPerDek;
        // TODO This ^^ must be > the maximum size of a batch to avoid an infinite loop
        this.keyContextCache = Caffeine.newBuilder()
                .buildAsync((key, executor) -> makeKeyContext(key));
        this.decryptorCache = new ConcurrentHashMap<>();
        this.encryptionVersion = EncryptionVersion.V1; // TODO read from config
        this.encryptionHeader = new Header[]{ new RecordHeader(ENCRYPTION_HEADER_NAME, new byte[]{ encryptionVersion.code() }) };
    }

    private CompletionStage<KeyContext> currentDekContext(@NonNull KekId kekId) {
        // todo should we add some scheduled timeout as well? or should we rely on the KMS to timeout appropriately.
        return keyContextCache.get(kekId);
    }

    private CompletableFuture<KeyContext> makeKeyContext(@NonNull KekId kekId) {
        return attemptMakeDekContext(kekId, 0);
    }

    private CompletableFuture<KeyContext> attemptMakeDekContext(@NonNull KekId kekId, int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return CompletableFuture.failedFuture(new EncryptorCreationException("failed to create encryptor after " + attempt + " attempts"));
        }
        return kms.generateDekPair(kekId)
                .thenApply(dekPair -> {
                    E edek = dekPair.edek();
                    short edekSize = (short) edekSerde.sizeOf(edek);
                    ByteBuffer serializedEdek = ByteBuffer.allocate(edekSize);
                    edekSerde.serialize(edek, serializedEdek);
                    serializedEdek.flip();

                    return new KeyContext(serializedEdek,
                            System.nanoTime() + dekTtlNanos,
                            maxEncryptionsPerDek,
                            // Either we have a different Aes encryptor for each thread
                            // or we need mutex
                            // or we externalize the state
                            AesGcmEncryptor.forEncrypt(new AesGcmIvGenerator(new SecureRandom()), dekPair.dek()));
                }).toCompletableFuture()
                // todo wire in a scheduler so we can delay/jitter DEK creation attempts
                .exceptionallyComposeAsync(throwable -> {
                    LOGGER.error("failed to create DEK encryption context for {} on attempt {}", kekId, attempt, throwable);
                    return attemptMakeDekContext(kekId, attempt + 1);
                });
    }

    @NonNull
    @Override
    @SuppressWarnings("java:S2445")
    public CompletionStage<Void> encrypt(@NonNull String topicName,
                                         int partition,
                                         @NonNull EncryptionScheme encryptionScheme,
                                         @NonNull List<? extends Record> records,
                                         @NonNull Receiver receiver) {
        if (records.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return attemptEncrypt(topicName, partition, encryptionScheme, records, receiver, 0);
    }

    @SuppressWarnings("java:S2445")
    private CompletionStage<Void> attemptEncrypt(String topicName, int partition, @NonNull EncryptionScheme encryptionScheme, @NonNull List<? extends Record> records,
                                                 @NonNull Receiver receiver, int attempt) {
        if (attempt >= MAX_ATTEMPTS) {
            return CompletableFuture.failedFuture(
                    new EncryptionException("failed to encrypt records for topic " + topicName + " partition " + partition + " after " + attempt + " attempts"));
        }
        return currentDekContext(encryptionScheme.kekId()).thenCompose(keyContext -> {
            synchronized (keyContext) {
                // if it's not alive we know a previous encrypt call has removed this stage from the cache and fall through to retry encrypt
                if (!keyContext.isDestroyed()) {
                    if (!keyContext.hasAtLeastRemainingEncryptions(records.size())) {
                        // remove the key context from the cache, then call encrypt again to drive caffeine to recreate it
                        rotateKeyContext(encryptionScheme, keyContext);
                    }
                    else {
                        // todo ensure that a failure during encryption terminates the entire operation with a failed future
                        return encrypt(encryptionScheme, records, receiver, keyContext);
                    }
                }
            }
            return attemptEncrypt(topicName, partition, encryptionScheme, records, receiver, attempt + 1);
        });
    }

    @NonNull
    private CompletableFuture<Void> encrypt(@NonNull EncryptionScheme encryptionScheme, @NonNull List<? extends Record> records,
                                            @NonNull Receiver receiver, KeyContext keyContext) {
        var maxParcelSize = records.stream()
                .mapToInt(kafkaRecord -> Parcel.sizeOfParcel(
                        encryptionVersion.parcelVersion(),
                        encryptionScheme.recordFields(),
                        kafkaRecord))
                .filter(value -> value > 0)
                .max()
                .orElseThrow();
        var maxWrapperSize = records.stream()
                .mapToInt(kafkaRecord -> sizeOfWrapper(keyContext, maxParcelSize))
                .filter(value -> value > 0)
                .max()
                .orElseThrow();
        ByteBuffer parcelBuffer = bufferPool.acquire(maxParcelSize);
        ByteBuffer wrapperBuffer = bufferPool.acquire(maxWrapperSize);
        try {
            encryptRecords(encryptionScheme, keyContext, records, parcelBuffer, wrapperBuffer, receiver);
        }
        finally {
            if (wrapperBuffer != null) {
                bufferPool.release(wrapperBuffer);
            }
            if (parcelBuffer != null) {
                bufferPool.release(parcelBuffer);
            }
        }
        keyContext.recordEncryptions(records.size());
        return CompletableFuture.completedFuture(null);
    }

    // this must only be called while holding the lock on this keycontext
    private void rotateKeyContext(@NonNull EncryptionScheme encryptionScheme, KeyContext keyContext) {
        keyContext.destroy();
        KekId kekId = encryptionScheme.kekId();
        keyContextCache.synchronous().invalidate(kekId);
    }

    private void encryptRecords(@NonNull EncryptionScheme encryptionScheme,
                                @NonNull KeyContext keyContext,
                                @NonNull List<? extends Record> records,
                                @NonNull ByteBuffer parcelBuffer,
                                @NonNull ByteBuffer wrapperBuffer,
                                @NonNull Receiver receiver) {
        records.forEach(kafkaRecord -> {
            if (encryptionScheme.recordFields().contains(RecordField.RECORD_HEADER_VALUES)
                    && kafkaRecord.headers().length > 0
                    && !kafkaRecord.hasValue()) {
                // todo implement header encryption preserving null record-values
                throw new IllegalStateException("encrypting headers prohibited when original record value null, we must preserve the null for tombstoning");
            }
            if (kafkaRecord.hasValue()) {
                Parcel.writeParcel(encryptionVersion.parcelVersion(), encryptionScheme.recordFields(), kafkaRecord, parcelBuffer);
                parcelBuffer.flip();
                var transformedValue = writeWrapper(keyContext, parcelBuffer, wrapperBuffer);
                Header[] headers = transformHeaders(encryptionScheme, kafkaRecord);
                receiver.accept(kafkaRecord, transformedValue, headers);
                wrapperBuffer.rewind();
                parcelBuffer.rewind();
            }
            else {
                receiver.accept(kafkaRecord, null, kafkaRecord.headers());
            }
        });
    }

    private Header[] transformHeaders(@NonNull EncryptionScheme encryptionScheme, Record kafkaRecord) {
        Header[] oldHeaders = kafkaRecord.headers();
        Header[] headers;
        if (encryptionScheme.recordFields().contains(RecordField.RECORD_HEADER_VALUES) || oldHeaders.length == 0) {
            headers = encryptionHeader;
        }
        else {
            headers = new Header[1 + oldHeaders.length];
            headers[0] = encryptionHeader[0];
            System.arraycopy(oldHeaders, 0, headers, 1, oldHeaders.length);
        }
        return headers;
    }

    private int sizeOfWrapper(KeyContext keyContext, int parcelSize) {
        var edek = keyContext.serializedEdek();
        return ByteUtils.sizeOfUnsignedVarint(edek.length)
                + edek.length
                + 1 // aad code
                + 1 // cipher code
                + keyContext.encodedSize(parcelSize);

    }

    @Nullable
    private ByteBuffer writeWrapper(KeyContext keyContext,
                                    ByteBuffer parcel,
                                    ByteBuffer wrapper) {
        switch (encryptionVersion.wrapperVersion()) {
            case V1 -> {
                var edek = keyContext.serializedEdek();
                ByteUtils.writeUnsignedVarint(edek.length, wrapper);
                wrapper.put(edek);
                wrapper.put(AadSpec.NONE.code()); // aadCode
                wrapper.put(CipherCode.AES_GCM_96_128.code());
                keyContext.encodedSize(parcel.limit());
                ByteBuffer aad = ByteUtils.EMPTY_BUF; // TODO pass the AAD to encode
                keyContext.encode(parcel, wrapper); // iv and ciphertext
            }
        }
        wrapper.flip();
        return wrapper;
    }

    /**
     * Reads the {@link #ENCRYPTION_HEADER_NAME} header from the record.
     * @param topicName The topic name.
     * @param partition The partition.
     * @param kafkaRecord The record.
     * @return The encryption header, or null if it's missing (indicating that the record wasn't encrypted).
     */
    static EncryptionVersion decryptionVersion(String topicName, int partition, Record kafkaRecord) {
        for (Header header : kafkaRecord.headers()) {
            if (ENCRYPTION_HEADER_NAME.equals(header.key())) {
                byte[] value = header.value();
                if (value.length != 1) {
                    throw new EncryptionException("Invalid value for header with key '" + ENCRYPTION_HEADER_NAME + "' "
                            + "in record at offset " + kafkaRecord.offset()
                            + " in partition " + partition
                            + " of topic " + topicName);
                }
                return EncryptionVersion.fromCode(value[0]);
            }
        }
        return null;
    }

    private CompletionStage<AesGcmEncryptor> getOrCacheDecryptor(E edek) {
        return decryptorCache.compute(edek, (k, v) -> {
            if (v == null) {
                return kms.decryptEdek(edek)
                        .thenApply(AesGcmEncryptor::forDecrypt).toCompletableFuture();
                // TODO what happens if the CS complete exceptionally
                // TODO what happens if the CS doesn't complete at all in a reasonably time frame?
            }
            else {
                return v;
            }
        });
    }

    @NonNull
    @Override
    public CompletionStage<Void> decrypt(String topicName,
                                         int partition,
                                         @NonNull List<? extends Record> records,
                                         @NonNull Receiver receiver) {
        List<CompletionStage<Void>> futures = new ArrayList<>(records.size());
        for (var kafkaRecord : records) {
            var decryptionVersion = decryptionVersion(topicName, partition, kafkaRecord);
            if (decryptionVersion == null) {
                receiver.accept(kafkaRecord, kafkaRecord.value(), kafkaRecord.headers());
                futures.add(CompletableFuture.completedFuture(null));
            }
            else {
                // right now (because we only support topic name based kek selection) once we've resolved the first value we
                // can keep the lock and process all the records
                ByteBuffer wrapper = kafkaRecord.value();
                var x = resolveEncryptor(decryptionVersion.wrapperVersion(), wrapper).thenAccept(encryptor -> {
                    decryptRecord(decryptionVersion, encryptor, wrapper, kafkaRecord, receiver);
                });
                futures.add(x);
            }
        }

        return io.kroxylicious.filter.encryption.EnvelopeEncryptionFilter.join(futures).thenAccept(list -> {
        });
    }

    @SuppressWarnings("java:S2445")
    private void decryptRecord(EncryptionVersion decryptionVersion,
                               AesGcmEncryptor encryptor,
                               ByteBuffer wrapper,
                               Record kafkaRecord,
                               @NonNull Receiver receiver) {
        var aadSpec = AadSpec.fromCode(wrapper.get());
        ByteBuffer aad = switch (aadSpec) {
            case NONE -> ByteUtils.EMPTY_BUF;
        };

        var cipherCode = CipherCode.fromCode(wrapper.get());

        ByteBuffer plaintextParcel;
        synchronized (encryptor) {
            plaintextParcel = decryptParcel(wrapper.slice(), encryptor);
        }
        Parcel.readParcel(decryptionVersion.parcelVersion(), plaintextParcel, kafkaRecord, receiver);
    }

    private CompletionStage<AesGcmEncryptor> resolveEncryptor(WrapperVersion wrapperVersion, ByteBuffer wrapper) {
        switch (wrapperVersion) {
            case V1:
                var edekLength = ByteUtils.readUnsignedVarint(wrapper);
                ByteBuffer slice = wrapper.slice(wrapper.position(), edekLength);
                var edek = edekSerde.deserialize(slice);
                wrapper.position(wrapper.position() + edekLength);
                return getOrCacheDecryptor(edek);
        }
        throw new EncryptionException("Unknown wrapper version " + wrapperVersion);
    }

    private ByteBuffer decryptParcel(ByteBuffer ciphertextParcel, AesGcmEncryptor encryptor) {
        ByteBuffer plaintext = ciphertextParcel.duplicate();
        encryptor.decrypt(ciphertextParcel, plaintext);
        plaintext.flip();
        return plaintext;
    }

}
