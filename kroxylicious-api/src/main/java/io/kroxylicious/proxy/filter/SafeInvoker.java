/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

/**
 * Wraps a delegate invoker so that onRequest and onResponse can be safely called even if this
 * Invoker does not want to handle this message, in this case the message will be forwarded without
 * the delegate doing anything with it.
 * @param invoker the delegate
 */
record SafeInvoker(FilterInvoker invoker) implements FilterInvoker {

    @Override
    public CompletionStage<RequestFilterCommand> onRequest(ApiKeys apiKey, short apiVersion, RequestHeaderData header, ApiMessage body, FilterContext filterContext) {
        if (invoker.shouldHandleRequest(apiKey, apiVersion)) {
            return invoker.onRequest(apiKey, apiVersion, header, body, filterContext);
        }
        else {
            return filterContext.forwardRequest(header, body);
        }
    }

    @Override
    public CompletionStage<ResponseFilterCommand> onResponse(ApiKeys apiKey, short apiVersion, ResponseHeaderData header, ApiMessage body, FilterContext filterContext) {
        if (invoker.shouldHandleResponse(apiKey, apiVersion)) {
            return invoker.onResponse(apiKey, apiVersion, header, body, filterContext);
        }
        else {
            return filterContext.responseFilterCommandBuilder().forward(header, body).completed();
        }
    }

    @Override
    public boolean shouldHandleRequest(ApiKeys apiKey, short apiVersion) {
        return invoker.shouldHandleRequest(apiKey, apiVersion);
    }

    @Override
    public boolean shouldHandleResponse(ApiKeys apiKey, short apiVersion) {
        return invoker.shouldHandleResponse(apiKey, apiVersion);
    }

}
