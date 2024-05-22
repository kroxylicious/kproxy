/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import io.kroxylicious.proxy.config.FilterDefinition;
import io.kroxylicious.proxy.config.PluginFactory;
import io.kroxylicious.proxy.config.PluginFactoryRegistry;
import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterAndInvoker;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Abstracts the creation of a chain of filter instances, hiding the configuration
 * required for instantiation at the point at which instances are created.
 * New instances are created during initialization of a downstream channel.
 */
public class FilterChainFactory implements AutoCloseable {

    private static final class Wrapper implements AutoCloseable {

        private final FilterFactory<? super Object, ? super Object> filterFactory;
        private final Object config;
        private final Object initResult;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Wrapper(FilterFactoryContext context,
                        String instanceName,
                        FilterFactory<? super Object, ? super Object> filterFactory,
                        Object config) {
            this.filterFactory = filterFactory;
            this.config = config;
            try {
                initResult = filterFactory.initialize(context, config);
            }
            catch (Exception e) {
                throw new PluginConfigurationException("Exception initializing filter factory " + instanceName + " with config " + config + ": " + e.getMessage(), e);
            }
        }

        public Filter create(FilterFactoryContext context) {
            if (closed.get()) {
                throw new IllegalStateException("Filter factory is closed");
            }
            try {
                return filterFactory.createFilter(context, initResult);
            }
            catch (Exception e) {
                throw new PluginConfigurationException("Exception instantiating filter using factory " + filterFactory, e);
            }
        }

        @Override
        public void close() {
            if (!this.closed.getAndSet(true)) {
                filterFactory.close(initResult);
            }
        }

        @Override
        public String toString() {
            return "Wrapper[" +
                    "filterFactory=" + filterFactory + ", " +
                    "config=" + config + ']';
        }

    }

    private final List<Wrapper> initialized;

    public FilterChainFactory(PluginFactoryRegistry pfr, List<FilterDefinition> filterDefinitions) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Class<FilterFactory<? super Object, ? super Object>> type = (Class) FilterFactory.class;
        PluginFactory<FilterFactory<? super Object, ? super Object>> pluginFactory = pfr.pluginFactory(type);
        if (filterDefinitions == null || ((Collection<FilterDefinition>) filterDefinitions).isEmpty()) {
            this.initialized = List.of();
        }
        else {
            FilterFactoryContext context = new FilterFactoryContext() {
                @Override
                public ScheduledExecutorService eventLoop() {
                    return null;
                }

                @Override
                public <P> @NonNull P pluginInstance(@NonNull Class<P> pluginClass, @NonNull String instanceName) {
                    return pfr.pluginFactory(pluginClass).pluginInstance(instanceName);
                }
            };
            this.initialized = new ArrayList<>(filterDefinitions.size());
            try {
                for (var fd : filterDefinitions) {
                    FilterFactory<? super Object, ? super Object> filterFactory = pluginFactory.pluginInstance(fd.type());
                    Class<?> configType = pluginFactory.configType(fd.type());
                    if (fd.config() == null || configType.isInstance(fd.config())) {
                        Wrapper uninitializedFilterFactory = new Wrapper(context, fd.type(), filterFactory, fd.config());
                        this.initialized.add(uninitializedFilterFactory);
                    }
                    else {
                        throw new PluginConfigurationException("accepts config of type " +
                                configType.getName() + " but provided with config of type " + fd.config().getClass().getName() + "]");
                    }
                }
            }
            catch (Exception e) {
                // close already initialized factories
                close();
                throw e;
            }
        }
    }

    @Override
    public void close() {
        RuntimeException firstThrown = null;
        for (Wrapper wrapper : initialized.reversed()) {
            try {
                wrapper.close();
            }
            catch (RuntimeException e) {
                if (firstThrown == null) {
                    firstThrown = e;
                }
                else {
                    firstThrown.addSuppressed(e);
                }
            }
        }
        if (firstThrown != null) {
            throw firstThrown;
        }
    }

    /**
     * Creates and returns a new chain of filter instances.
     *
     * @return the new chain.
     */
    public List<FilterAndInvoker> createFilters(FilterFactoryContext context) {
        return initialized
                .stream()
                .flatMap(wrapper -> FilterAndInvoker.build(wrapper.create(context)).stream())
                .toList();
    }
}
