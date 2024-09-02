/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.kroxylicious.inmemory;

import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.plugin.Plugin;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * <p>A service interface for {@link InMemoryKms} useful for integration testing.
 * Unlike {@link UnitTestingKmsService}, the life time of state shared between instances of {@link Kms} produced
 * from this service is <strong>not</strong> coupled to the instance of this class.
 * Instead each set of states is named.
 * This allows you to configure a KMS in a filter config (to be instantiated by the proxy) and then obtain
 * a reference to the <em>same state</em> in your test code,
 * even though different instances of {@code IntegrationTestingKmsService} were used.</p>
 *
 * <p>You can obtain an instance via {@link ServiceLoader} or just use the factory method
 * {@link #newInstance()}.</p>
 *
 * <p>Users are encouraged to:</p>
 * <ul>
 *     <li>Use random UUIDs to name their instances</li>
 *     <li>Delete their instances using {@link #delete(String)} when tearing down tests</li>
 * </ul>
 * @see UnitTestingKmsService
 */
@Plugin(configType = IntegrationTestingKmsService.Config.class)
public class IntegrationTestingKmsService implements KmsService<IntegrationTestingKmsService.Config, IntegrationTestingKmsService.Init, UUID, InMemoryEdek> {

    public static IntegrationTestingKmsService newInstance() {
        return (IntegrationTestingKmsService) ServiceLoader.load(KmsService.class).stream()
                .filter(p -> p.type() == IntegrationTestingKmsService.class)
                .findFirst()
                .map(ServiceLoader.Provider::get)
                .orElse(null);
    }

    public record Config(
                         String name) {
        public Config {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException();
            }
        }
    }

    public record Init(
                       Config config) {
        public Init {
            Objects.requireNonNull(config);
        }
    }

    private static final Map<String, InMemoryKms> KMSES = new ConcurrentHashMap<>();

    @Override
    public Init initialize(Config config) {
        return new Init(config);
    }

    @NonNull
    @Override
    public InMemoryKms buildKms(Init initializationData) {
        return KMSES.computeIfAbsent(initializationData.config().name(), ignored -> new InMemoryKms(12, 128, Map.of(), Map.of()));
    }

    public static void delete(String name) {
        KMSES.remove(name);
    }

}
