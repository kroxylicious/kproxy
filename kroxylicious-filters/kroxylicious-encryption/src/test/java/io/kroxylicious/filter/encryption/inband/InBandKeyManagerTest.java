/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.inband;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.kroxylicious.filter.encryption.EncryptionScheme;
import io.kroxylicious.filter.encryption.Receiver;
import io.kroxylicious.filter.encryption.RecordField;
import io.kroxylicious.kms.provider.kroxylicious.inmemory.InMemoryKms;
import io.kroxylicious.kms.provider.kroxylicious.inmemory.UnitTestingKmsService;
import io.kroxylicious.kms.service.DekPair;
import io.kroxylicious.kms.service.Serde;

import edu.umd.cs.findbugs.annotations.NonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InBandKeyManagerTest {

    @Test
    void shouldBeAbleToDependOnRecordHeaderEquality() {
        // The InBandKeyManager relies internally on RecordHeader implementing equals
        // Since it's Kafka's class let's validate that here
        var rh = new RecordHeader("foo", new byte[]{ 7, 4, 1 });
        var rh2 = new RecordHeader("foo", new byte[]{ 7, 4, 1 });
        var rh3 = new RecordHeader("bar", new byte[]{ 3, 3 });

        assertEquals(rh, rh2);
        assertNotEquals(rh, rh3);
        assertNotEquals(rh2, rh3);
    }

    @Test
    void prefixBufferShouldBeReleasedAfterGeneratingPrefix() {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        BufferPool pool = Mockito.mock(BufferPool.class);
        AtomicReference<ByteBuffer> onlyBuffer = new AtomicReference<>();
        when(pool.acquire(anyInt())).thenAnswer(invocationOnMock -> {
            int argument = invocationOnMock.getArgument(0);
            ByteBuffer buffer = ByteBuffer.wrap(new byte[argument]);
            assertThat(onlyBuffer.compareAndSet(null, buffer)).isTrue();
            return buffer;
        });

        var km = new InBandKeyManager<>(kms, pool, 500_000);

        var kekId = kms.generateKey();
        CompletionStage<KeyContext> keyContextCompletionStage = km.currentDekContext(kekId);
        assertThat(keyContextCompletionStage).succeedsWithin(Duration.ofSeconds(5L));
        KeyContext context = keyContextCompletionStage.toCompletableFuture().join();
        assertThat(context.prefix() != null);
        assertThat(onlyBuffer).isNotNull();
        verify(pool).acquire(anyInt());
        verify(pool).release(onlyBuffer.get());
    }

    @Test
    void headers() {
        var myHeader = new RecordHeader("mine", new byte[]{ 1 });
        var nonEmpty = new Header[]{ myHeader };
        var fieldsHeader = InBandKeyManager.createEncryptedFieldsHeader(EnumSet.of(RecordField.RECORD_VALUE));

        var r1 = InBandKeyManager.prependToHeaders(new Header[0], fieldsHeader);
        assertEquals(1, r1.length);
        assertEquals(fieldsHeader, r1[0]);

        var r2 = InBandKeyManager.prependToHeaders(nonEmpty, fieldsHeader);
        assertEquals(2, r2.length);
        assertEquals(fieldsHeader, r2[0]);
        assertEquals(myHeader, r2[1]);

        var x0 = InBandKeyManager.removeInitialHeaders(r1, 1);
        assertEquals(0, x0.length);

        var x1 = InBandKeyManager.removeInitialHeaders(r2, 1);
        assertEquals(1, x1.length);
        assertEquals(myHeader, x1[0]);
    }

    @Test
    void shouldEncryptRecordValue() {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 500_000);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        TestingRecord record = new TestingRecord(value);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted));
        record.value().rewind();
        assertEquals(1, encrypted.size());
        assertNotEquals(initial, encrypted);
        // TODO add assertion on headers

        List<TestingRecord> decrypted = new ArrayList<>();
        km.decrypt(encrypted, recordReceivedRecord(decrypted));

        assertEquals(initial, decrypted);
    }

    @Test
    void encryptionRetry() {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var kekId = kms.generateKey();
        // configure 1 encryption per dek but then try to encrypt 2 records, will destroy and retry
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 1);

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        var value2 = ByteBuffer.wrap(new byte[]{ 4, 5, 6 });
        TestingRecord record = new TestingRecord(value);
        TestingRecord record2 = new TestingRecord(value2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        CompletionStage encrypt = km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted));
        assertThat(encrypt).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining("failed to encrypt records after 3 attempts");
    }

    @NonNull
    private static Receiver recordReceivedRecord(Collection<TestingRecord> list) {
        return (r, v, h) -> {
            list.add(new TestingRecord(copyBytes(v), h));
        };
    }

    @Test
    void shouldEncryptRecordValueForMultipleRecords() throws ExecutionException, InterruptedException, TimeoutException {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 500_000);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        TestingRecord record = new TestingRecord(value);

        var value2 = ByteBuffer.wrap(new byte[]{ 3, 4, 5 });
        TestingRecord record2 = new TestingRecord(value2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                (r, v, h) -> {
                    encrypted.add(new TestingRecord(copyBytes(v), h));
                }).toCompletableFuture().get(10, TimeUnit.SECONDS);
        record.value().rewind();
        record2.value().rewind();
        assertEquals(2, encrypted.size());
        assertNotEquals(initial, encrypted);
        // TODO add assertion on headers

        List<TestingRecord> decrypted = new ArrayList<>();
        km.decrypt(encrypted, recordReceivedRecord(decrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(initial, decrypted);
    }

    @Test
    void shouldGenerateNewDekIfOldDekHasNoRemainingEncryptions() throws ExecutionException, InterruptedException, TimeoutException {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 2);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        TestingRecord record = new TestingRecord(value);

        var value2 = ByteBuffer.wrap(new byte[]{ 3, 4, 5 });
        TestingRecord record2 = new TestingRecord(value2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        record.value().rewind();
        record2.value().rewind();

        // at this point we have encrypted 2 records with the manager set to maximum 2 encryptions per dek

        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        record.value().rewind();
        record2.value().rewind();

        assertThat(kms.numDeksGenerated()).isEqualTo(2);
        var edekOne = getSerializedGeneratedEdek(kms, 0);
        var edekTwo = getSerializedGeneratedEdek(kms, 1);
        assertThat(encrypted).hasSize(4);
        List<TestingDek> deks = extractEdeks(encrypted);
        assertThat(deks).containsExactly(edekOne, edekOne, edekTwo, edekTwo);
    }

    @Test
    void shouldGenerateNewDekIfOldOneHasSomeRemainingEncryptionsButNotEnoughForWholeBatch() throws ExecutionException, InterruptedException, TimeoutException {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 3);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        TestingRecord record = new TestingRecord(value);

        var value2 = ByteBuffer.wrap(new byte[]{ 3, 4, 5 });
        TestingRecord record2 = new TestingRecord(value2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        record.value().rewind();
        record2.value().rewind();

        // at this point we have encrypted 2 records with the manager set to maximum 3 encryptions per dek, so we need a new dek to encrypt 2 more records

        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        record.value().rewind();
        record2.value().rewind();
        assertThat(kms.numDeksGenerated()).isEqualTo(2);
        var edekOne = getSerializedGeneratedEdek(kms, 0);
        var edekTwo = getSerializedGeneratedEdek(kms, 1);
        assertThat(encrypted).hasSize(4);
        List<TestingDek> deks = extractEdeks(encrypted);
        assertThat(deks).containsExactly(edekOne, edekOne, edekTwo, edekTwo);
    }

    @Test
    void shouldUseSameDekForMultipleBatches() throws ExecutionException, InterruptedException, TimeoutException {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 4);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        TestingRecord record = new TestingRecord(value);

        var value2 = ByteBuffer.wrap(new byte[]{ 3, 4, 5 });
        TestingRecord record2 = new TestingRecord(value2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        record.value().rewind();
        record2.value().rewind();

        // at this point we have encrypted 2 records with the manager set to maximum 4 encryptions per dek, so we do not need a new dek to encrypt 2 more records

        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_VALUE)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);

        record.value().rewind();
        record2.value().rewind();
        assertThat(kms.numDeksGenerated()).isEqualTo(1);
        var edekOne = getSerializedGeneratedEdek(kms, 0);
        assertThat(encrypted).hasSize(4);
        List<TestingDek> deks = extractEdeks(encrypted);
        assertThat(deks).containsExactly(edekOne, edekOne, edekOne, edekOne);
    }

    @NonNull
    private static ByteBuffer copyBytes(ByteBuffer v) {
        byte[] bytes = new byte[v.remaining()];
        v.get(bytes);
        ByteBuffer wrap = ByteBuffer.wrap(bytes);
        return wrap;
    }

    @Test
    void shouldEncryptRecordHeaders() {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 500_000);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        var headers = new Header[]{ new RecordHeader("foo", new byte[]{ 4, 5, 6 }) };
        TestingRecord record = new TestingRecord(value, headers);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_HEADER_VALUES)),
                initial,
                recordReceivedRecord(encrypted));
        value.rewind();

        assertEquals(1, encrypted.size());
        assertNotEquals(initial, encrypted);

        List<TestingRecord> decrypted = new ArrayList<>();
        km.decrypt(encrypted, recordReceivedRecord(decrypted));

        assertEquals(List.of(new TestingRecord(value, new Header[]{ new RecordHeader("foo", new byte[]{ 4, 5, 6 }) })), decrypted);
    }

    @Test
    void shouldEncryptRecordHeadersForMultipleRecords() throws ExecutionException, InterruptedException, TimeoutException {
        var kmsService = UnitTestingKmsService.newInstance();
        InMemoryKms kms = kmsService.buildKms(new UnitTestingKmsService.Config());
        var km = new InBandKeyManager<>(kms, BufferPool.allocating(), 500_000);

        var kekId = kms.generateKey();

        var value = ByteBuffer.wrap(new byte[]{ 1, 2, 3 });
        var headers = new Header[]{ new RecordHeader("foo", new byte[]{ 4, 5, 6 }) };
        TestingRecord record = new TestingRecord(value, headers);
        var value2 = ByteBuffer.wrap(new byte[]{ 7, 8, 9 });
        var headers2 = new Header[]{ new RecordHeader("foo", new byte[]{ 10, 11, 12 }) };
        TestingRecord record2 = new TestingRecord(value2, headers2);

        List<TestingRecord> encrypted = new ArrayList<>();
        List<TestingRecord> initial = List.of(record, record2);
        km.encrypt(new EncryptionScheme<>(kekId, EnumSet.of(RecordField.RECORD_HEADER_VALUES)),
                initial,
                recordReceivedRecord(encrypted)).toCompletableFuture().get(10, TimeUnit.SECONDS);
        value.rewind();
        value2.rewind();
        assertEquals(2, encrypted.size());
        assertNotEquals(initial, encrypted);

        List<TestingRecord> decrypted = new ArrayList<>();
        km.decrypt(encrypted, recordReceivedRecord(decrypted));

        assertEquals(List.of(new TestingRecord(value, new Header[]{ new RecordHeader("foo", new byte[]{ 4, 5, 6 }) }),
                new TestingRecord(value2, new Header[]{ new RecordHeader("foo", new byte[]{ 10, 11, 12 }) })), decrypted);
    }

    public TestingDek getSerializedGeneratedEdek(InMemoryKms kms, int i) {
        DekPair generatedEdek = kms.getGeneratedEdek(i);
        var edek = generatedEdek.edek();
        Serde serde = kms.edekSerde();
        int size = serde.sizeOf(edek);
        ByteBuffer buffer = ByteBuffer.allocate(size + 2);
        buffer.putShort((short) size);
        serde.serialize(edek, buffer);
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return new TestingDek(bytes);
    }

    @NonNull
    private static List<TestingDek> extractEdeks(List<TestingRecord> encrypted) {
        List<TestingDek> deks = encrypted.stream()
                .map(testingRecord -> Arrays.stream(testingRecord.headers()).filter(header -> header.key().equals("kroxylicious.io/dek")).findFirst().orElseThrow()
                        .value())
                .map(TestingDek::new)
                .toList();
        return deks;
    }

}
