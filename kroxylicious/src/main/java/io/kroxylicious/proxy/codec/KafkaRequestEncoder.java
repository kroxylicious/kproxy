/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kroxylicious.proxy.codec;

import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

public class KafkaRequestEncoder extends KafkaMessageEncoder<RequestFrame> {
    private static final Logger LOGGER = LogManager.getLogger(KafkaRequestEncoder.class);
    public static final int LENGTH = 4;
    public static final int API_KEY = 2;
    public static final int API_VERSION = 2;
    private final CorrelationManager correlationManager;

    public KafkaRequestEncoder(CorrelationManager correlationManager) {
        this.correlationManager = correlationManager;
    }

    @Override
    protected Logger log() {
        return LOGGER;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RequestFrame frame, ByteBuf out) throws Exception {
        super.encode(ctx, frame, out);
        // TODO re-reading from the encoded buffer like this is ugly
        // probably better to just include apiKey and apiVersion in the frame
        var ri = out.readerIndex();
        var wi = out.writerIndex();
        out.readerIndex(LENGTH);
        short apiKey = out.readShort();
        short apiVersion = out.readShort();
        boolean hasResponse = hasResponse(frame, out, ri, apiKey, apiVersion);
        boolean decodeResponse = frame.decodeResponse();
        int upstreamCorrelationId = correlationManager.putBrokerRequest(apiKey,
                apiVersion,
                frame.correlationId(),
                hasResponse,
                decodeResponse);
        out.writerIndex(LENGTH + API_KEY + API_VERSION);
        out.writeInt(upstreamCorrelationId);
        out.readerIndex(ri);
        out.writerIndex(wi);

        if (decodeResponse &&
                !hasResponse) {
            log().warn("{}: Not honouring decode of acks=0 PRODUCE response, because there will be none", ctx);
        }
    }

    private boolean hasResponse(RequestFrame frame, ByteBuf out, int ri, short apiKey, short apiVersion) {
        if (frame instanceof DecodedRequestFrame) {
            return apiKey != ApiKeys.PRODUCE.id
                    || ((ProduceRequestData) ((DecodedRequestFrame) frame).body).acks() != 0;
        }
        else {
            return apiKey != ApiKeys.PRODUCE.id
                    || readAcks(out, ri, apiKey, apiVersion) != 0;
        }
    }

    static short readAcks(ByteBuf in, int startOfMessage, short apiKey, short apiVersion) {
        // Annoying case: we need to know whether to expect a response so that we know
        // whether to add to the correlation (so that, in turn, we know how to rewrite the correlation
        // id of the client response).
        // Adding ack-less Produce requests to the correlation => OOME.
        // This requires decoding at least the first one or two
        // fields of all Produce requests.
        // Because we want to avoid parsing the produce request using ProduceRequestData
        // just for this we are stuck with hand coding deserialization code...
        // final int keyVersionAndCorrIdSize = 8;
        // final int startOfBodyOffset;
        short headerVersion = ApiKeys.forId(apiKey).requestHeaderVersion(apiVersion);
        incrementReaderIndex(in, 4);
        if (headerVersion >= 1) {
            int clientIdLength = in.readShort();
            incrementReaderIndex(in, clientIdLength);
        }
        if (headerVersion >= 2) {
            int numTaggedFields = ByteBufAccessor.readUnsignedVarint(in);
            for (int i = 0; i < numTaggedFields; i++) {
                int tag = ByteBufAccessor.readUnsignedVarint(in);
                int size = ByteBufAccessor.readUnsignedVarint(in);
                incrementReaderIndex(in, size);
            }
        }

        final short acks;
        if (apiVersion < 3) {
            acks = in.readShort();
        }
        else { // Transactional id comes before acks
            int transactionIdLength;
            if (apiVersion < 9) { // Last non-flexible version
                transactionIdLength = in.readShort();
            }
            else if (apiVersion <= 9) { // First flexible version
                transactionIdLength = ByteBufAccessor.readUnsignedVarint(in);
            }
            else {
                throw new AssertionError("Unsupported Produce apiVersion: " + apiVersion);
            }
            incrementReaderIndex(in, transactionIdLength);
            acks = in.readShort();
        }
        // Reset index
        in.readerIndex(startOfMessage);
        return acks;
    }

    private static void incrementReaderIndex(ByteBuf byteBuf, int increment) {
        byteBuf.readerIndex(byteBuf.readerIndex() + increment);
    }

}
