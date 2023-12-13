/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.kroxylicious.inmemory;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import io.kroxylicious.kms.provider.kroxylicious.inmemory.IntegrationTestingKmsService.Config;
import io.kroxylicious.kms.service.TestKekManager;
import io.kroxylicious.kms.service.TestKmsFacade;
import io.kroxylicious.kms.service.UnknownAliasException;

public class InMemoryTestKmsFacade implements TestKmsFacade<Config, UUID, InMemoryEdek> {

    private final UUID kmsId = UUID.randomUUID();
    private InMemoryKms kms;

    @Override
    public void start() {
        kms = IntegrationTestingKmsService.newInstance().buildKms(new Config(kmsId.toString()));
    }

    @Override
    public void stop() {
        IntegrationTestingKmsService.delete(kmsId.toString());
    }

    @Override
    public TestKekManager getTestKekManager() {
        return new TestKekManager() {
            @Override
            public void generateKek(String alias) {
                Objects.requireNonNull(alias);

                try {
                    kms.resolveAlias(alias).toCompletableFuture().join();
                    throw new AlreadyExistsException("key with alias " + alias + " already exists");
                }
                catch (CompletionException e) {
                    if (e.getCause() instanceof UnknownAliasException) {
                        var kekId = kms.generateKey();
                        kms.createAlias(kekId, alias);
                    }
                    else {
                        throw e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause());
                    }
                }
            }

            @Override
            public void rotateKek(String alias) {
                Objects.requireNonNull(alias);

                try {
                    kms.resolveAlias(alias).toCompletableFuture().join();
                    var kekId = kms.generateKey();
                    kms.createAlias(kekId, alias);
                }
                catch (CompletionException e) {
                    throw e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause());
                }
            }

            @Override
            public boolean exists(String alias) {
                try {
                    kms.resolveAlias(alias).toCompletableFuture().join();
                    return true;
                }
                catch (CompletionException e) {
                    if (e.getCause() instanceof UnknownAliasException) {
                        return false;
                    }
                    throw e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause());
                }
            }
        };
    }

    @Override
    public Class<IntegrationTestingKmsService> getKmsServiceClass() {
        return IntegrationTestingKmsService.class;
    }

    @Override
    public InMemoryKms getKms() {
        return kms;
    }

    @Override
    public Config getKmsServiceConfig() {
        return new Config(kmsId.toString());
    }
}
