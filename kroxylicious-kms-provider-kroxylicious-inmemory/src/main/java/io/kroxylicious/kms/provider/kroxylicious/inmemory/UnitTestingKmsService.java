/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.kroxylicious.inmemory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.kroxylicious.kms.service.DestroyableRawSecretKey;
import io.kroxylicious.kms.service.Kms;
import io.kroxylicious.kms.service.KmsService;
import io.kroxylicious.proxy.plugin.Plugin;

import edu.umd.cs.findbugs.annotations.NonNull;

import static java.util.stream.Collectors.toMap;

/**
 * <p>A service interface for {@link InMemoryKms} useful for unit testing.
 * An instance of this class encapsulates the set of keys and aliases which will be shared between
 * all {@link Kms} instances created from the same service instance.
 * A different instance of this class will have an independent set of keys and aliases.</p>
 *
 * <p>You can obtain an instance via {@link ServiceLoader} or just use the factory method
 * {@link #newInstance()}.</p>
 *
 *
 * @see IntegrationTestingKmsService
 */
@Plugin(configType = UnitTestingKmsService.Config.class)
public class UnitTestingKmsService implements KmsService<UnitTestingKmsService.Config, UnitTestingKmsService.Init, UUID, InMemoryEdek> {
    private final Map<Init, InMemoryKms> kmsMap = new ConcurrentHashMap<>();

    public static UnitTestingKmsService newInstance() {
        return (UnitTestingKmsService) ServiceLoader.load(KmsService.class).stream()
                .filter(p -> p.type() == UnitTestingKmsService.class)
                .findFirst()
                .map(ServiceLoader.Provider::get)
                .orElse(null);
    }

    @SuppressWarnings("java:S6218") // we currently don't need equals/hash to consider key contents
    public record Kek(
                      @JsonProperty(required = true) String uuid,
                      @JsonProperty(required = true) byte[] key,
                      @JsonProperty(required = true) String algorithm,
                      @JsonProperty(required = true) String alias) {}

    public record Config(
                         int numIvBytes,
                         int numAuthBits,
                         List<Kek> existingKeks) {
        public Config {
            if (numIvBytes < 1) {
                throw new IllegalArgumentException();
            }
            if (numAuthBits < 1) {
                throw new IllegalArgumentException();
            }
        }

        public Config() {
            this(12, 128, List.of());
        }
    }

    public record Init(
                       Config config) {
        public Init {
            Objects.requireNonNull(config);
        }
    }

    @Override
    public Init initialize(Config config) {
        return new Init(config);
    }

    @NonNull
    @Override
    public InMemoryKms buildKms(Init initializationData) {
        return kmsMap.computeIfAbsent(initializationData, init -> {
            List<Kek> kekDefs = init.config().existingKeks();
            Map<UUID, DestroyableRawSecretKey> keys = kekDefs.stream()
                    .collect(toMap(k -> UUID.fromString(k.uuid), k -> DestroyableRawSecretKey.takeCopyOf(k.key, k.algorithm)));
            Map<String, UUID> aliases = kekDefs.stream().collect(toMap(k -> k.alias, k -> UUID.fromString(k.uuid)));
            return new InMemoryKms(init.config().numIvBytes(),
                    init.config().numAuthBits(),
                    keys, aliases);
        });
    }

}
