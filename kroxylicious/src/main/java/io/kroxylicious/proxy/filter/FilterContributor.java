/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import io.kroxylicious.proxy.config.ProxyConfig;

public interface FilterContributor {

    Class<? extends FilterConfig> getConfigType(String shortName);

    KrpcFilter getFilter(String shortName, ProxyConfig proxyConfig, FilterConfig filterConfig);
}
