/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.test.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.protocol.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks api version for requests
 */
public class CorrelationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationManager.class);

    final Map<Integer, Correlation> brokerRequests = new HashMap<>();

    /**
     * Creates an empty CorrelationManager
     */
    public CorrelationManager() {
    }

    /**
     * Allocate and return a correlation id for an outgoing request to the broker.
     *
     * @param apiKey         The API key.
     * @param apiVersion     The API version.
     * @param correlationId  The request's correlation id.
     * @param responseFuture The future to complete with the response
     */
    public void putBrokerRequest(short apiKey,
                                 short apiVersion,
                                 int correlationId, CompletableFuture<SequencedResponse> responseFuture) {
        Correlation existing = this.brokerRequests.put(correlationId, new Correlation(apiKey, apiVersion, responseFuture));
        if (existing != null) {
            LOGGER.error("Duplicate upstream correlation id {}", correlationId);
        }
    }

    /**
     * Find (and remove) the Correlation for an incoming response from the broker
     * @param correlationId The correlation id in the response.
     * @return the correlation
     */
    public Correlation getBrokerCorrelation(int correlationId) {
        return brokerRequests.remove(correlationId);
    }

    /**
     * A record for which responses should be decoded, together with their
     * API key and version.
     */
    public record Correlation(short apiKey,
                              short apiVersion,
                              CompletableFuture<SequencedResponse> responseFuture) {

        @Override
        public String toString() {
            return "Correlation(" +
                    "apiKey=" + ApiKeys.forId(apiKey) +
                    ", apiVersion=" + apiVersion +
                    ')';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Correlation that = (Correlation) o;
            return apiKey == that.apiKey && apiVersion == that.apiVersion;
        }

        @Override
        public int hashCode() {
            return Objects.hash(apiKey, apiVersion);
        }

    }
}
