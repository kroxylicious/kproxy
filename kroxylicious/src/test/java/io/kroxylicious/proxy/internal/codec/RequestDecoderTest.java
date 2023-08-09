/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.codec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import io.kroxylicious.proxy.filter.ApiVersionsRequestFilter;
import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.OpaqueRequestFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestDecoderTest extends AbstractCodecTest {

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsExactlyOneFrame_decoded(short apiVersion) throws Exception {
        assertEquals(12,
                exactlyOneFrame_decoded(apiVersion,
                        ApiKeys.API_VERSIONS::requestHeaderVersion,
                        AbstractCodecTest::exampleRequestHeader,
                        AbstractCodecTest::exampleApiVersionsRequest,
                        AbstractCodecTest::deserializeRequestHeaderUsingKafkaApis,
                        AbstractCodecTest::deserializeApiVersionsRequestUsingKafkaApis,
                        new KafkaRequestDecoder(
                                DecodePredicate
                                        .forFilters(FilterAndInvoker.build(
                                                (ApiVersionsRequestFilter) (version, header, request, context) -> context.requestFilterResultBuilder()
                                                        .forward(header, request).completed()))),
                        DecodedRequestFrame.class,
                        (RequestHeaderData header) -> header),
                "Unexpected correlation id");
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsExactlyOneFrame_opaque(short apiVersion) throws Exception {
        assertEquals(12,
                exactlyOneFrame_encoded(apiVersion,
                        ApiKeys.API_VERSIONS::requestHeaderVersion,
                        AbstractCodecTest::exampleRequestHeader,
                        AbstractCodecTest::exampleApiVersionsRequest,
                        new KafkaRequestDecoder(DecodePredicate.forFilters(
                                FilterAndInvoker.build(new ApiVersionsRequestFilter() {

                                    @Override
                                    public boolean shouldHandleApiVersionsRequest(short apiVersion) {
                                        return false;
                                    }

                                    @Override
                                    public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header,
                                                                                                     ApiVersionsRequestData request,
                                                                                                     KrpcFilterContext context) {
                                        return context.requestFilterResultBuilder().forward(header, request).completed();
                                    }
                                }))),
                        OpaqueRequestFrame.class),
                "Unexpected correlation id");
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsFrameLessOneByte(short apiVersion) throws Exception {
        RequestHeaderData encodedHeader = exampleRequestHeader(apiVersion);
        ApiVersionsRequestData encodedBody = exampleApiVersionsRequest();

        short headerVersion = ApiKeys.forId(ApiKeys.API_VERSIONS.id).requestHeaderVersion(apiVersion);
        ByteBuffer bbuffer = serializeUsingKafkaApis(headerVersion, encodedHeader, apiVersion, encodedBody);

        ByteBuf byteBuf = Unpooled.wrappedBuffer(bbuffer.limit(bbuffer.limit() - 1));

        var messages = new ArrayList<>();
        new KafkaRequestDecoder(
                DecodePredicate.forFilters(
                        FilterAndInvoker.build((ApiVersionsRequestFilter) (version, header, request, context) -> {
                            return context.requestFilterResultBuilder().forward(header, request).completed();
                        })))
                .decode(null, byteBuf, messages);

        assertEquals(List.of(), messageClasses(messages));
        assertEquals(0, byteBuf.readerIndex());
    }

    private void doTestApiVersionsFrameFirstNBytes(short apiVersion, int n, int expectRead) throws Exception {
        RequestHeaderData encodedHeader = exampleRequestHeader(apiVersion);
        ApiVersionsRequestData encodedBody = exampleApiVersionsRequest();

        short headerVersion = ApiKeys.forId(ApiKeys.API_VERSIONS.id).requestHeaderVersion(apiVersion);
        ByteBuffer bbuffer = serializeUsingKafkaApis(headerVersion, encodedHeader, apiVersion, encodedBody);

        ByteBuf byteBuf = Unpooled.wrappedBuffer(bbuffer.limit(n));

        var messages = new ArrayList<>();
        new KafkaRequestDecoder(
                DecodePredicate.forFilters(
                        FilterAndInvoker
                                .build((ApiVersionsRequestFilter) (version, header, request, context) -> context.requestFilterResultBuilder().forward(header, request)
                                        .completed())))
                .decode(null, byteBuf, messages);

        assertEquals(List.of(), messageClasses(messages));
        assertEquals(expectRead, byteBuf.readerIndex());
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsFrameFirst3Bytes(short apiVersion) throws Exception {
        doTestApiVersionsFrameFirstNBytes(apiVersion, 3, 0);
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsFrameFirst5Bytes(short apiVersion) throws Exception {
        doTestApiVersionsFrameFirstNBytes(apiVersion, 5, 0);
    }

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersionsExactlyTwoFrames(short apiVersion) throws Exception {
        RequestHeaderData encodedHeader = exampleRequestHeader(apiVersion);

        ApiVersionsRequestData encodedBody = exampleApiVersionsRequest();

        short headerVersion = ApiKeys.forId(ApiKeys.API_VERSIONS.id).requestHeaderVersion(apiVersion);
        ByteBuffer bbuffer = serializeUsingKafkaApis(2, headerVersion, encodedHeader, apiVersion, encodedBody);

        // This is a bit of a hack... the Data classes know about which fields appear in which versions
        // So use Kafka to deserialize the messages we just serialised using Kafka, so that we
        // have objects we can use assertEquals on later
        bbuffer.mark();
        bbuffer.getInt(); // frame size
        var header = deserializeRequestHeaderUsingKafkaApis(headerVersion, bbuffer);
        var body = deserializeApiVersionsRequestUsingKafkaApis(apiVersion, bbuffer);
        bbuffer.reset();

        ByteBuf byteBuf = Unpooled.wrappedBuffer(bbuffer);

        var messages = new ArrayList<>();
        new KafkaRequestDecoder(
                DecodePredicate.forFilters(
                        FilterAndInvoker
                                .build((ApiVersionsRequestFilter) (version, head, request, context) -> context.requestFilterResultBuilder().forward(header, request)
                                        .completed())))
                .decode(null, byteBuf, messages);

        assertEquals(List.of(DecodedRequestFrame.class, DecodedRequestFrame.class), messageClasses(messages));
        DecodedRequestFrame frame = (DecodedRequestFrame) messages.get(0);
        assertEquals(header, frame.header());
        assertEquals(body, frame.body());
        frame = (DecodedRequestFrame) messages.get(1);
        assertEquals(header, frame.header());
        assertEquals(body, frame.body());

        assertEquals(byteBuf.writerIndex(), byteBuf.readerIndex());
    }
}
