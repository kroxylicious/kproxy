/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.filter;

import java.util.Set;

import org.apache.kafka.common.message.MetadataRequestData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.filter.KrpcFilterContext;
import io.kroxylicious.proxy.filter.RequestFilter;

/**
 * An internal filter that causes the system to eagerly learn the cluster's topology by spontaneously emitting
 * an out-of-band Metadata request at the earliest legal point in the Kafka conversation.  The response to allows
 * the Endpoint reconciliation to take place so that restricted upstream bindings are replaced by true bindings to
 * the actual upstream brokers.
 * <br/>
 * Once the bindings are made, the filter causes the client's connection to close.   This is done
 * in order to force the client to reconnect, thus ensuring the client has a connection to the intended broker.
 *
 * @see io.kroxylicious.proxy.internal.net.EndpointRegistry
 */
public class EagerMetadataLearner implements RequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EagerMetadataLearner.class);

    /**
     * The set of the API keys that are permitted before the client would normally send a METADATA request.
     */
    private final static Set<ApiKeys> KAFKA_PRELUDE = Set.of(ApiKeys.API_VERSIONS, ApiKeys.SASL_HANDSHAKE, ApiKeys.SASL_AUTHENTICATE);

    public EagerMetadataLearner() {
    }

    @Override
    public void onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        if (KAFKA_PRELUDE.contains(apiKey)) {
            filterContext.forwardRequest(header, body);
        }
        else {
            final short apiVersion = determineMetadataApiVersion(header);
            // Send an out-of-band Metadata request. The response will be intercepted by the in-built BrokerAddressFilter.
            // By the time control returns to the handler, the upstream addresses will have been reconciled.
            boolean useClientRequest = apiKey.equals(ApiKeys.METADATA) && apiVersion == header.requestApiVersion();
            var request = useClientRequest ? (MetadataRequestData) body : new MetadataRequestData();

            var unused = filterContext.<MetadataResponseData> replaceRequest(apiVersion, request)
                    .thenAccept(metadataResponseContext -> {
                        if (useClientRequest) {
                            // The client's requested matched our out-of-band request, so we may as well return the
                            // response.
                            metadataResponseContext.context().forwardResponse(metadataResponseContext.apiMessage());
                        }
                        // closing the connection is important. This client connection is connected to bootstrap (it could
                        // be any broker or maybe not something else). we must close the connection to force the client to
                        // connect again.
                        metadataResponseContext.context().closeConnection();
                        LOGGER.info("Closing upstream bootstrap connection {} now that endpoint reconciliation is complete.", filterContext.channelDescriptor());
                    });
        }
    }

    private short determineMetadataApiVersion(RequestHeaderData header) {
        final short apiVersion;
        if (header.requestApiKey() == ApiKeys.METADATA.id) {
            apiVersion = header.requestApiVersion();
        }
        else {
            // TODO: use a version appearing the intersection calculated by ApiVersionFilter.
            apiVersion = MetadataRequestData.LOWEST_SUPPORTED_VERSION;
        }
        return apiVersion;
    }

}
