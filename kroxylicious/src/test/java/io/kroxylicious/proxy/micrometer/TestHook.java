/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.micrometer;

import org.testcontainers.shaded.org.apache.commons.lang3.NotImplementedException;

import io.micrometer.core.instrument.MeterRegistry;

import io.kroxylicious.proxy.config.BaseConfig;
import io.kroxylicious.proxy.service.ContributorContext;

public record TestHook(String shortName, BaseConfig config, ContributorContext context) implements MicrometerConfigurationHook {
    @Override
    public void configure(MeterRegistry targetRegistry) {
        throw new NotImplementedException("not implemented!");
    }
}
