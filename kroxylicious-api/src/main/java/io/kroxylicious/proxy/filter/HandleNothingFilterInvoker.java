/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

record HandleNothingFilterInvoker() implements FilterInvoker {
    static final FilterInvoker INSTANCE = new HandleNothingFilterInvoker();

    @Override
    public void onRequest(ApiKeys apiKey, short apiVersion, RequestHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        throw new IllegalStateException("I should never be invoked");
    }

    @Override
    public void onResponse(ApiKeys apiKey, short apiVersion, ResponseHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        throw new IllegalStateException("I should never be invoked");
    }
}
