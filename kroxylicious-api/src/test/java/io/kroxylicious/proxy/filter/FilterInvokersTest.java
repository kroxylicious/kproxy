/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class FilterInvokersTest {

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void testInvalidFilters(KrpcFilter invalid) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> FilterInvokers.from(invalid));
    }

    @ParameterizedTest
    @MethodSource("validFilters")
    void testValidFilters(KrpcFilter invalid) {
        assertThat(FilterInvokers.from(invalid)).isNotNull();
    }

    @Test
    void testCompositeFilter() {
        MultipleSpecificFilter filterA = new MultipleSpecificFilter();
        RequestResponseFilter filterB = new RequestResponseFilter();
        RequestFilter filterC = (apiKey, header, body, filterContext) -> null;
        ResponseFilter filterD = (apiKey, header, body, filterContext) -> null;
        List<FilterAndInvoker> from = FilterInvokers.from(
                (CompositeFilter) () -> List.of((CompositeFilter) () -> List.of(filterA, filterB), (CompositeFilter) () -> List.of(filterC, filterD)));
        List<KrpcFilter> filters = from.stream().map(FilterAndInvoker::filter).toList();
        assertThat(filters).containsExactly(filterA, filterB, filterC, filterD);
    }

    public static Stream<KrpcFilter> invalidFilters() {
        KrpcFilter noKrpcFilterSubinterfacesImplemented = new KrpcFilter() {

        };
        return Stream.of(noKrpcFilterSubinterfacesImplemented,
                new SpecificAndRequestFilter(),
                new SpecificAndResponseFilter(),
                new SpecificAndCompositeFilter(),
                new CompositeAndRequestFilter(),
                new CompositeAndResponseFilter(),
                new TooDeeplyNestedCompositeFilter(),
                new SelfReferencingCompositeFilter());
    }

    public static Stream<KrpcFilter> validFilters() {
        ApiVersionsRequestFilter singleSpecificMessageFilter = (apiVersion, header, request, context) -> null;
        RequestFilter requestFilter = (apiKey, header, body, filterContext) -> null;
        ResponseFilter responseFilter = (apiKey, header, body, filterContext) -> null;
        return Stream.of(singleSpecificMessageFilter,
                new MultipleSpecificFilter(),
                requestFilter,
                responseFilter,
                new RequestResponseFilter(),
                new NoRecursionCompositeFilter(),
                new SingleRecursionCompositeFilter());
    }

    static class RequestResponseFilter implements RequestFilter, ResponseFilter {

        @Override
        public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage request, KrpcFilterContext context) {
            return null;
        }

        @Override
        public CompletionStage<ResponseFilterResult> onResponse(ApiKeys apiKey, ResponseHeaderData header, ApiMessage response, KrpcFilterContext context) {
            return null;
        }
    }

    static class NoRecursionCompositeFilter implements CompositeFilter {

        @Override
        public List<KrpcFilter> getFilters() {
            return List.of(new MultipleSpecificFilter());
        }
    }

    static class SingleRecursionCompositeFilter implements CompositeFilter {

        @Override
        public List<KrpcFilter> getFilters() {
            return List.of((CompositeFilter) () -> List.of(new MultipleSpecificFilter()), (CompositeFilter) () -> List.of(new MultipleSpecificFilter()));
        }
    }

    static class MultipleSpecificFilter implements ApiVersionsRequestFilter, ApiVersionsResponseFilter {

        @Override
        public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                         KrpcFilterContext context) {

            return null;
        }

        @Override
        public CompletionStage<ResponseFilterResult> onApiVersionsResponse(short apiVersion, ResponseHeaderData header, ApiVersionsResponseData response,
                                                                           KrpcFilterContext context) {

            return null;
        }
    }

    static class CompositeAndRequestFilter implements RequestFilter, CompositeFilter {
        @Override
        public List<KrpcFilter> getFilters() {
            return null;
        }

        @Override
        public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage request, KrpcFilterContext context) {

            return null;
        }
    }

    /**
     * We do not want to explode Composite Filters forever
     */
    static class SelfReferencingCompositeFilter implements CompositeFilter {
        @Override
        public List<KrpcFilter> getFilters() {
            return List.of(this);
        }
    }

    static class TooDeeplyNestedCompositeFilter implements CompositeFilter {
        @Override
        public List<KrpcFilter> getFilters() {
            return List.of((CompositeFilter) () -> List.of((CompositeFilter) () -> List.of((ApiVersionsRequestFilter) (apiVersion, header, request, context) -> null)));
        }
    }

    static class CompositeAndResponseFilter implements ResponseFilter, CompositeFilter {
        @Override
        public List<KrpcFilter> getFilters() {
            return null;
        }

        @Override
        public CompletionStage<ResponseFilterResult> onResponse(ApiKeys apiKey, ResponseHeaderData header, ApiMessage response, KrpcFilterContext context) {

            return null;
        }
    }

    static class SpecificAndCompositeFilter implements ApiVersionsRequestFilter, CompositeFilter {

        @Override
        public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                         KrpcFilterContext context) {

            return null;
        }

        @Override
        public List<KrpcFilter> getFilters() {
            return null;
        }
    }

    static class SpecificAndResponseFilter implements ApiVersionsRequestFilter, ResponseFilter {

        @Override
        public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                         KrpcFilterContext context) {

            return null;
        }

        @Override
        public CompletionStage<ResponseFilterResult> onResponse(ApiKeys apiKey, ResponseHeaderData header, ApiMessage response, KrpcFilterContext context) {

            return null;
        }
    }

    static class SpecificAndRequestFilter implements ApiVersionsRequestFilter, RequestFilter {

        @Override
        public CompletionStage<RequestFilterResult> onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage request, KrpcFilterContext context) {

            return null;
        }

        @Override
        public CompletionStage<RequestFilterResult> onApiVersionsRequest(short apiVersion, RequestHeaderData header, ApiVersionsRequestData request,
                                                                         KrpcFilterContext context) {

            return null;
        }
    }

}