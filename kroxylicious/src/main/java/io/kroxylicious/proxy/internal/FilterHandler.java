/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.FilterInvoker;
import io.kroxylicious.proxy.filter.FilterResult;
import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.filter.RequestFilterResult;
import io.kroxylicious.proxy.filter.ResponseFilterResult;
import io.kroxylicious.proxy.frame.DecodedFrame;
import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;
import io.kroxylicious.proxy.frame.OpaqueRequestFrame;
import io.kroxylicious.proxy.frame.OpaqueResponseFrame;
import io.kroxylicious.proxy.internal.util.Assertions;
import io.kroxylicious.proxy.model.VirtualCluster;

/**
 * A {@code ChannelInboundHandler} (for handling requests from downstream)
 * that applies a single {@link KrpcFilter}.
 */
public class FilterHandler extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterHandler.class);
    private final KrpcFilter filter;
    private final long timeoutMs;
    private final String sniHostname;
    private final Channel inboundChannel;
    private final VirtualCluster virtualCluster;
    private final FilterInvoker invoker;
    private CompletableFuture<Void> writeFuture = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> readFuture = CompletableFuture.completedFuture(null);

    public FilterHandler(FilterAndInvoker filterAndInvoker, long timeoutMs, String sniHostname, VirtualCluster virtualCluster, Channel inboundChannel) {
        this.filter = Objects.requireNonNull(filterAndInvoker).filter();
        this.invoker = filterAndInvoker.invoker();
        this.timeoutMs = Assertions.requireStrictlyPositive(timeoutMs, "timeout");
        this.sniHostname = sniHostname;
        this.virtualCluster = virtualCluster;
        this.inboundChannel = inboundChannel;
    }

    String filterDescriptor() {
        return filter.getClass().getSimpleName() + "@" + System.identityHashCode(filter);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof InternalRequestFrame<?>) {
            // jump the queue, internal request must flow!
            doWrite(ctx, msg, promise);
        }
        else if (writeFuture.isDone()) {
            writeFuture = doWrite(ctx, msg, promise);
        }
        else {
            writeFuture = writeFuture.whenComplete((a, b) -> {
                if (ctx.channel().isOpen()) {
                    doWrite(ctx, msg, promise);
                }
            });
        }
    }

    private CompletableFuture<Void> doWrite(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof DecodedRequestFrame<?> decodedFrame) {
            var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, promise, timeoutMs, sniHostname, virtualCluster);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}: Dispatching downstream {} request to filter{}: {}",
                        ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
            }

            var stage = invoker.onRequest(decodedFrame.apiKey(), decodedFrame.apiVersion(), decodedFrame.header(),
                    decodedFrame.body(), filterContext);
            if (stage == null) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("{}: Filter{} for {} request unexpectedly returned null. This is a coding error in the filter. Closing connection.",
                            ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                }
                filterContext.closeConnection();
                return CompletableFuture.completedFuture(null);
            }
            var future = stage.toCompletableFuture();
            boolean defer = !future.isDone();
            var maybeDeferred = defer ? handleDeferredStage(ctx, future, decodedFrame) : future;
            var execute = executeWrite(ctx, decodedFrame, filterContext, maybeDeferred);
            var maybeDeferredCompleted = defer ? handleDeferredWriteCompletion(ctx, execute) : execute;
            return maybeDeferredCompleted.thenApply(filterResult -> null);
        }
        else {
            if (!(msg instanceof OpaqueRequestFrame)
                    && msg != Unpooled.EMPTY_BUFFER) {
                // Unpooled.EMPTY_BUFFER is used by KafkaProxyFrontendHandler#closeOnFlush
                // but otherwise we don't expect any other kind of message
                LOGGER.warn("Unexpected message writing to upstream: {}", msg, new IllegalStateException());
            }
            ctx.write(msg, promise);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<RequestFilterResult> executeWrite(ChannelHandlerContext ctx, DecodedRequestFrame<?> decodedFrame,
                                                                DefaultFilterContext filterContext,
                                                                CompletableFuture<RequestFilterResult> filterFuture) {
        return filterFuture.whenComplete((requestFilterResult, t) -> {
            // maybe better to run the whole thing on the netty thread.

            if (t != null) {
                LOGGER.warn("{}: Filter{} for {} request ended exceptionally - closing connection",
                        ctx.channel(), filterDescriptor(), decodedFrame.apiKey(), t);
                filterContext.closeConnection();
                return;
            }
            if (requestFilterResult == null) {
                LOGGER.warn("{}: Filter{} for {} request future completed with null - closing connection",
                        ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                filterContext.closeConnection();
                return;
            }

            if (requestFilterResult.drop()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Filter{} drops {} request",
                            ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                }
                return;
            }

            if (requestFilterResult.message() != null) {
                if (requestFilterResult.shortCircuitResponse()) {
                    forwardShortCircuitResponse(ctx, decodedFrame, filterContext, requestFilterResult);
                }
                else {
                    var header = requestFilterResult.header() == null ? decodedFrame.header() : requestFilterResult.header();
                    filterContext.forwardRequestInternal((RequestHeaderData) header, requestFilterResult.message());
                }
            }

            if (requestFilterResult.closeConnection()) {
                if (requestFilterResult.message() != null) {
                    ctx.flush();
                }
                filterContext.closeConnection();
            }
        });
    }

    private <T extends FilterResult> CompletableFuture<T> handleDeferredStage(ChannelHandlerContext ctx, CompletableFuture<T> stage,
                                                                              DecodedFrame<?, ?> decodedFrame) {
        inboundChannel.config().setAutoRead(false);
        var timeoutFuture = ctx.executor().schedule(() -> {
            LOGGER.warn("{}: Filter {} was timed-out whilst processing {} {}", ctx.channel(), filterDescriptor(),
                    decodedFrame instanceof DecodedRequestFrame ? "request" : "response", decodedFrame.apiKey());
            stage.completeExceptionally(new TimeoutException("Filter %s was timed-out.".formatted(filterDescriptor())));
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return stage.thenApply(filterResult -> {
            timeoutFuture.cancel(false);
            return filterResult;
        });
    }

    private void forwardShortCircuitResponse(ChannelHandlerContext ctx, DecodedRequestFrame<?> decodedFrame, DefaultFilterContext filterContext,
                                             RequestFilterResult requestFilterResult) {
        if (decodedFrame.hasResponse()) {
            var header = requestFilterResult.header() == null ? new ResponseHeaderData() : ((ResponseHeaderData) requestFilterResult.header());
            header.setCorrelationId(decodedFrame.correlationId());
            filterContext.forwardResponseInternal(header, requestFilterResult.message());
        }
        else {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}: Filter {} attempted to short-circuit respond to a message with apiKey {}" +
                        " that has no response in the Kafka Protocol, dropping response",
                        ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof InternalResponseFrame<?> decodedFrame) {
            // jump the queue, let extra requests flow back to their sender
            if (decodedFrame.isRecipient(filter)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Completing {} response for request sent by this filter{}: {}",
                            ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
                }
                CompletableFuture<ApiMessage> p = decodedFrame.promise();
                p.complete(decodedFrame.body());
            }
            else {
                doRead(ctx, msg);
            }
        }
        else if (readFuture.isDone()) {
            readFuture = doRead(ctx, msg);
        }
        else {
            readFuture = readFuture.whenComplete((a, b) -> {
                if (ctx.channel().isOpen()) {
                    doRead(ctx, msg);
                }
            });
        }
    }

    private CompletableFuture<Void> doRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof DecodedResponseFrame<?> decodedFrame) {
            var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, null, timeoutMs, sniHostname, virtualCluster);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{}: Dispatching upstream {} response to filter {}: {}",
                        ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
            }
            var stage = invoker.onResponse(decodedFrame.apiKey(), decodedFrame.apiVersion(),
                    decodedFrame.header(), decodedFrame.body(), filterContext);
            if (stage == null) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn("{}: Filter{} for {} response unexpectedly returned null. This is a coding error in the filter. Closing connection.",
                            ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                }
                filterContext.closeConnection();
                return CompletableFuture.completedFuture(null);
            }
            var future = stage.toCompletableFuture();
            boolean defer = !future.isDone();
            var maybeDeferred = defer ? handleDeferredStage(ctx, future, decodedFrame) : future;
            var execute = executeRead(ctx, decodedFrame, filterContext, maybeDeferred);
            var maybeDeferredCompleted = defer ? handleDeferredReadCompletion(execute) : execute;
            return maybeDeferredCompleted.thenApply(responseFilterResult -> null);
        }
        else {
            if (!(msg instanceof OpaqueResponseFrame)) {
                LOGGER.warn("Unexpected message reading from upstream: {}", msg, new IllegalStateException());
            }
            ctx.fireChannelRead(msg);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<?> handleDeferredReadCompletion(CompletableFuture<?> execute) {
        return execute.whenComplete((ignored, throwable) -> {
            inboundChannel.config().setAutoRead(true);
            inboundChannel.flush();
        });
    }

    private CompletableFuture<?> handleDeferredWriteCompletion(ChannelHandlerContext ctx, CompletableFuture<?> execute) {
        return execute.whenComplete((ignored, throwable) -> {
            inboundChannel.config().setAutoRead(true);
            ctx.flush();
            // flush inbound in case of short-circuit
            inboundChannel.flush();
        });
    }

    private CompletableFuture<ResponseFilterResult> executeRead(ChannelHandlerContext ctx, DecodedResponseFrame<?> decodedFrame, DefaultFilterContext filterContext,
                                                                CompletableFuture<ResponseFilterResult> filterFuture) {
        return filterFuture.whenComplete((responseFilterResult, t) -> {
            if (t != null) {
                LOGGER.warn("{}: Filter{} for {} response ended exceptionally - closing connection",
                        ctx.channel(), filterDescriptor(), decodedFrame.apiKey(), t);
                filterContext.closeConnection();
                return;
            }
            if (responseFilterResult == null) {
                LOGGER.warn("{}: Filter{} for {} response future completed with null - closing connection",
                        ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                filterContext.closeConnection();
                return;
            }
            if (responseFilterResult.drop()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Filter{} drops {} response",
                            ctx.channel(), filterDescriptor(), decodedFrame.apiKey());
                }
                return;
            }

            if (responseFilterResult.message() != null) {
                ResponseHeaderData header = responseFilterResult.header() == null ? decodedFrame.header() : (ResponseHeaderData) responseFilterResult.header();
                filterContext.forwardResponseInternal(header, responseFilterResult.message());
            }

            if (responseFilterResult.closeConnection()) {
                filterContext.closeConnection();
            }
        });
    }

}
