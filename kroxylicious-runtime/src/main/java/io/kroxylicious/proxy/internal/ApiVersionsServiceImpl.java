/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion;
import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersionCollection;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.ApiVersionsService;
import io.kroxylicious.proxy.filter.FilterContext;

public class ApiVersionsServiceImpl {

    private record ApiVersions(ApiVersionCollection upstream, ApiVersionCollection intersected) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiVersionsServiceImpl.class);
    private ApiVersions apiVersions = null;

    public void updateVersions(String channel, ApiVersionsResponseData upstreamApiVersions) {
        var upstream = upstreamApiVersions.duplicate().apiKeys();
        intersectApiVersions(channel, upstreamApiVersions);
        var intersected = upstreamApiVersions.duplicate().apiKeys();
        this.apiVersions = new ApiVersions(upstream, intersected);
    }

    private static void intersectApiVersions(String channel, ApiVersionsResponseData resp) {
        Set<ApiVersion> unknownApis = new HashSet<>();
        for (var key : resp.apiKeys()) {
            short apiId = key.apiKey();
            if (ApiKeys.hasId(apiId)) {
                ApiKeys apiKey = ApiKeys.forId(apiId);
                intersectApiVersion(channel, key, apiKey);
            }
            else {
                unknownApis.add(key);
            }
        }
        resp.apiKeys().removeAll(unknownApis);
    }

    /**
     * Update the given {@code key}'s max and min versions so that the client uses APIs versions mutually
     * understood by both the proxy and the broker.
     * @param channel The channel.
     * @param key The key data from an upstream API_VERSIONS response.
     * @param apiKey The proxy's API key for this API.
     */
    private static void intersectApiVersion(String channel, ApiVersionsResponseData.ApiVersion key, ApiKeys apiKey) {
        short mutualMin = (short) Math.max(
                key.minVersion(),
                apiKey.messageType.lowestSupportedVersion());
        if (mutualMin != key.minVersion()) {
            LOGGER.trace("{}: {} min version changed to {} (was: {})", channel, apiKey, mutualMin, key.maxVersion());
            key.setMinVersion(mutualMin);
        }
        else {
            LOGGER.trace("{}: {} min version unchanged (is: {})", channel, apiKey, mutualMin);
        }

        short mutualMax = (short) Math.min(
                key.maxVersion(),
                apiKey.messageType.highestSupportedVersion(true));
        if (mutualMax != key.maxVersion()) {
            LOGGER.trace("{}: {} max version changed to {} (was: {})", channel, apiKey, mutualMin, key.maxVersion());
            key.setMaxVersion(mutualMax);
        }
        else {
            LOGGER.trace("{}: {} max version unchanged (is: {})", channel, apiKey, mutualMin);
        }
    }

    public CompletionStage<Optional<ApiVersionsService.ApiVersionRanges>> getApiVersionRanges(ApiKeys keys, FilterContext context) {
        return getVersions(context).thenApply(versions -> {
            ApiVersion upstream = versions.upstream.find(keys.id);
            ApiVersion intersected = versions.intersected.find(keys.id);
            if (upstream == null || intersected == null) {
                return Optional.empty();
            }
            else {
                return Optional.of(new ApiVersionsService.ApiVersionRanges(upstream, intersected));
            }
        });
    }

    private CompletionStage<ApiVersions> getVersions(FilterContext context) {
        if (apiVersions != null) {
            return CompletableFuture.completedFuture(apiVersions);
        }
        else {
            var data = new ApiVersionsRequestData();
            var header = new RequestHeaderData().setRequestApiVersion(data.highestSupportedVersion());
            return context.<ApiVersionsResponseData> sendRequest(header, data).thenApply(response -> {
                updateVersions(context.channelDescriptor(), response);
                return apiVersions;
            });
        }
    }

}
