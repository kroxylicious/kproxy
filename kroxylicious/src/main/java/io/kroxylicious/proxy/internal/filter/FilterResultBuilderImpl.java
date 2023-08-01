/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.protocol.ApiMessage;

import io.kroxylicious.proxy.filter.filterresultbuilder.CloseStage;
import io.kroxylicious.proxy.filter.FilterResult;
import io.kroxylicious.proxy.filter.FilterResultBuilder;
import io.kroxylicious.proxy.filter.filterresultbuilder.TerminalStage;

public abstract class FilterResultBuilderImpl<H extends ApiMessage, FR extends FilterResult>
        implements FilterResultBuilder<H, FR>, CloseStage<FR> {
    private ApiMessage message;
    private ApiMessage header;
    private boolean closeConnection;
    private boolean drop;

    protected FilterResultBuilderImpl() {
    }

    @Override
    public CloseStage<FR> forward(H header, ApiMessage message) {
        validateForward(header, message);
        this.header = header;
        this.message = message;

        return this;
    }

    protected void validateForward(H header, ApiMessage message) {
    }

    ApiMessage header() {
        return header;
    }

    ApiMessage message() {
        return message;
    }

    @Override
    public TerminalStage<FR> withCloseConnection() {
        this.closeConnection = true;
        return this;
    }

    boolean closeConnection() {
        return closeConnection;
    }

    @Override
    public TerminalStage<FR> drop() {
        this.drop = true;
        return this;
    }

    public boolean isDrop() {
        return drop;
    }

    @Override
    public CompletionStage<FR> completed() {
        return CompletableFuture.completedStage(build());
    }
}
