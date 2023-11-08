/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion;
import org.apache.kafka.common.protocol.ApiKeys;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Service used to obtain the upstream cluster's ApiVersions and intersect them with the versions
 * supported by the proxy. Filter Authors can then use this information when sending extra requests
 * to the upstream cluster.
 * @deprecated functionality to be moved up into FilterContext
 */
@Deprecated(since = "0.3.0", forRemoval = true)
public interface ApiVersionsService {

    /**
     * Information about version ranges for an ApiKey supported by the upstream and Kroxylicious
     * @param upstream the version range supported by the upstream server, or null if this key is not supported by upstream
     * @param intersected the version range supported by both Kroxylicious and the upstream server, or null if this ApiKey is not supported
     * @deprecated upstream versions not very useful, so can be replaced with a return type of ApiVersion and only return intersected
     */
    @Deprecated(since = "0.3.0", forRemoval = true)
    record ApiVersionRanges(@NonNull ApiVersion upstream, @NonNull ApiVersion intersected) {}

    /**
     * Get the supported version ranges for an ApiKey. Will contain the upstream supported
     * version range, and the intersected version range supported by the proxy and upstream.
     * Filters will likely want to work with the intersected range as both the proxy and the
     * upstream can parse those versions.
     * @param keys keys
     * @return a CompletionStage that will be completed with the upstream ApiVersionRanges,
     * or an empty optional if the upstream doesn't support this key
     */
    CompletionStage<Optional<ApiVersionRanges>> getApiVersionRanges(ApiKeys keys);
}
