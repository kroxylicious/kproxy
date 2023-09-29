/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter.multitenant;

import io.kroxylicious.proxy.filter.FilterCreationContext;
import io.kroxylicious.proxy.filter.FilterFactory;

public class MultiTenantTransformationFilterFactory extends FilterFactory<MultiTenantTransformationFilter, Void> {

    public MultiTenantTransformationFilterFactory() {
        super(Void.class, MultiTenantTransformationFilter.class);
    }

    @Override
    public MultiTenantTransformationFilter createFilter(FilterCreationContext context, Void configuration) {
        return new MultiTenantTransformationFilter();
    }
}
