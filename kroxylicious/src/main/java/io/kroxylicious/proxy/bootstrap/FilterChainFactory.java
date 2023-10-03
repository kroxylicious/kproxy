/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.bootstrap;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.kroxylicious.proxy.config.FilterDefinition;
import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.FilterCreationContext;
import io.kroxylicious.proxy.filter.InvalidFilterConfigurationException;
import io.kroxylicious.proxy.internal.filter.NettyFilterContext;
import io.kroxylicious.proxy.service.FilterFactoryManager;

/**
 * Abstracts the creation of a chain of filter instances, hiding the configuration
 * required for instantiation at the point at which instances are created.
 * New instances are created during initialization of a downstream channel.
 */
public class FilterChainFactory {
    private final List<FilterDefinition> filterDefinitions;

    public FilterChainFactory(List<FilterDefinition> filterDefinitions) {
        validateFilterDefinitions(filterDefinitions);
        this.filterDefinitions = Optional.ofNullable(filterDefinitions).orElse(List.of());
    }

    /**
     * Validate that a collection of FilterDefinitions contains all the required configuration to configure the filter.
     * @param filterDefinitions the candidates to validate.
     * @throws InvalidFilterConfigurationException If there are problems.
     */
    public static void validateFilterDefinitions(Collection<FilterDefinition> filterDefinitions) {
        if (filterDefinitions == null || filterDefinitions.isEmpty()) {
            return;
        }
        var invalidFilters = filterDefinitions.stream()
                .filter(Predicate.not(FilterDefinition::isDefinitionValid))
                .map(FilterDefinition::type)
                .collect(Collectors.toSet());
        if (!invalidFilters.isEmpty()) {
            throw new InvalidFilterConfigurationException(invalidFilters.stream()
                    .collect(Collectors.joining(", ", "Invalid config for [", "]")));
        }
    }

    /**
     * Create a new chain of filter instances
     *
     * @return the new chain.
     */
    public List<FilterAndInvoker> createFilters(NettyFilterContext context) {
        return filterDefinitions
                .stream()
                .map(f -> {
                    FilterCreationContext wrap = context;
                    return FilterFactoryManager.INSTANCE.createInstance(f.type(), wrap, f.config());
                })
                .flatMap(filter -> FilterAndInvoker.build(filter).stream())
                .toList();
    }
}
