/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.micrometer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;

public class StandardBindersHook implements MicrometerConfigurationHook {
    private static final Logger log = LoggerFactory.getLogger(StandardBindersHook.class);
    private final StandardBindersHookConfig config;
    private final List<AutoCloseable> closeableBinders = new CopyOnWriteArrayList<>();

    public static class StandardBindersHookConfig {
        private final List<String> binderNames;

        @JsonCreator
        public StandardBindersHookConfig(List<String> binderNames) {
            this.binderNames = binderNames == null ? List.of() : binderNames;
        }
    }

    public StandardBindersHook(StandardBindersHookConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config should be non-null");
        }
        this.config = config;
    }

    @Override
    public void configure(MeterRegistry targetRegistry) {
        for (String binderName : this.config.binderNames) {
            MeterBinder binder = getBinder(binderName);
            binder.bindTo(targetRegistry);
            if (binder instanceof AutoCloseable closeable) {
                this.closeableBinders.add(closeable);
            }
            log.info("bound {} to micrometer registry", binderName);
        }

    }

    @Override
    public void close() {
        closeableBinders.forEach(closeable -> {
            try {
                closeable.close();
            }
            catch (Exception e) {
                log.warn("Ignoring exception whilst closing standard binder {}", closeable.getClass(), e);
            }
        });
    }

    /* testing */ protected MeterBinder getBinder(String binderName) {
        return switch (binderName) {
            case "UptimeMetrics" -> new UptimeMetrics();
            case "ProcessorMetrics" -> new ProcessorMetrics();
            case "FileDescriptorMetrics" -> new FileDescriptorMetrics();
            case "ClassLoaderMetrics" -> new ClassLoaderMetrics();
            case "JvmCompilationMetrics" -> new JvmCompilationMetrics();
            case "JvmGcMetrics" -> new JvmGcMetrics();
            case "JvmHeapPressureMetrics" -> new JvmHeapPressureMetrics();
            case "JvmInfoMetrics" -> new JvmInfoMetrics();
            case "JvmMemoryMetrics" -> new JvmMemoryMetrics();
            case "JvmThreadMetrics" -> new JvmThreadMetrics();
            default -> throw new IllegalArgumentException("no binder available for " + binderName);
        };
    }

}
