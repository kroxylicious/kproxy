/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.service;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.config.FilterDefinition;
import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterCreationContext;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.InvalidFilterConfigurationException;

public class FilterFactoryManager {
    private static final Logger logger = LoggerFactory.getLogger(FilterDefinition.class);

    public static final FilterFactoryManager INSTANCE = new FilterFactoryManager();
    private final Map<String, FilterFactory<?, ?>> filterFactories;

    private FilterFactoryManager() {
        ServiceLoader<FilterFactory<?, ?>> factories = serviceLoader();
        HashMap<String, FilterFactory<?, ?>> nameToFactory = new HashMap<>();
        for (FilterFactory<?, ?> factory : factories) {
            Class<?> serviceType = factory.filterType();
            Set<String> names = Set.of(serviceType.getName(), serviceType.getSimpleName());
            names.forEach(name -> {
                FilterFactory<?, ?> previous = nameToFactory.put(name, factory);
                if (previous != null) {
                    throw new IllegalStateException("more than one FilterFactory offers Filter named: " + name);
                }
            });
        }
        filterFactories = nameToFactory;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ServiceLoader<FilterFactory<?, ?>> serviceLoader() {
        return (ServiceLoader<FilterFactory<?, ?>>) (ServiceLoader) ServiceLoader.load(FilterFactory.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Filter createInstance(String typeName, FilterCreationContext constructionContext, Object config) {
        return ((FilterFactory) getFactory(typeName)).createFilter(constructionContext, config);
    }

    public Class<?> getConfigType(String typeName) {
        return getFactory(typeName).configType();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean validateConfig(String typeName, Object config) {
        try {
            FilterFactory factory = filterFactories.get(typeName);
            factory.validateConfiguration(config);
            return true;
        }
        catch (InvalidFilterConfigurationException e) {
            logger.warn("Invalid configuration supplied for {}", typeName, e);
            return false;
        }
    }

    private FilterFactory<?, ?> getFactory(String typeName) {
        FilterFactory<?, ?> factory = filterFactories.get(typeName);
        if (factory == null) {
            throw new IllegalArgumentException("no FilterFactory registered for typeName: " + typeName);
        }
        return factory;
    }

}
