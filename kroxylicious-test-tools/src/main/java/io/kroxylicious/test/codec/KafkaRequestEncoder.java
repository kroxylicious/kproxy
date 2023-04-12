/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.test.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import io.kroxylicious.test.client.CorrelationManager;

/**
 * Kafka Request Encoder
 */
public class KafkaRequestEncoder extends KafkaMessageEncoder<DecodedRequestFrame<?>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaRequestEncoder.class);

    private final CorrelationManager correlationManager;

    /**
     * Create KafkaRequestEncoder
     * @param correlationManager manager for tracking the apiKey and apiVersion per correlationId
     */
    public KafkaRequestEncoder(CorrelationManager correlationManager) {
        this.correlationManager = correlationManager;
    }

    @Override
    protected Logger log() {
        return LOGGER;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, DecodedRequestFrame frame, ByteBuf out) throws Exception {
        super.encode(ctx, frame, out);
        // not sure if this testing client needs to know that acks=0 produce requests don't get responses
        correlationManager.putBrokerRequest(frame.apiKey().id, frame.apiVersion(), frame.correlationId());
    }

}
