/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.inband;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.record.Record;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import io.kroxylicious.filter.encryption.ParcelVersion;
import io.kroxylicious.filter.encryption.RecordField;
import io.kroxylicious.filter.encryption.records.BatchAwareMemoryRecordsBuilder;
import io.kroxylicious.test.record.RecordTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class ParcelTest {

    static Stream<Arguments> shouldRoundTrip() {
        return Stream.of(
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE), RecordTestUtils.record((ByteBuffer) null)),
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }))), // no headers
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }),
                        new RecordHeader("foo", null))), // header with null value
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }),
                        new RecordHeader("foo", new byte[]{ 4, 5, 6 }))), // header with non-null value

                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE, RecordField.RECORD_HEADER_VALUES), RecordTestUtils.record((ByteBuffer) null)),
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE, RecordField.RECORD_HEADER_VALUES), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }))), // no headers
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE, RecordField.RECORD_HEADER_VALUES), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }),
                        new RecordHeader("foo", null))), // header with null value
                Arguments.of(EnumSet.of(RecordField.RECORD_VALUE, RecordField.RECORD_HEADER_VALUES), RecordTestUtils.record(ByteBuffer.wrap(new byte[]{ 1, 2, 3 }),
                        new RecordHeader("foo", new byte[]{ 4, 5, 6 }))) // header with non-null value
        );
    }

    @ParameterizedTest
    @MethodSource
    void shouldRoundTrip(Set<RecordField> fields, Record record) {
        var expectedValue = record.hasValue() ? record.value().duplicate() : null;
        int size = Parcel.sizeOfParcel(ParcelVersion.V1, fields, record);
        var buffer = ByteBuffer.allocate(size);
        Parcel.writeParcel(ParcelVersion.V1, fields, record, buffer);
        assertThat(buffer.remaining()).isEqualTo(0);

        buffer.flip();

        BatchAwareMemoryRecordsBuilder mockBuilder = Mockito.mock(BatchAwareMemoryRecordsBuilder.class);
        Parcel.readParcel(ParcelVersion.V1, buffer, record, (v, h) -> {
            mockBuilder.appendWithOffset(record.offset(), record.timestamp(), record.key(), v, h);
        });
        verify(mockBuilder).appendWithOffset(record.offset(), record.timestamp(), record.key(), expectedValue, record.headers());
        assertThat(buffer.remaining()).isEqualTo(0);
    }

}
