/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.aws.kms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import io.kroxylicious.kms.provider.aws.kms.config.Config;
import io.kroxylicious.kms.service.AbstractTestKmsFacadeTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class AwsKmsTestKmsFacadeTest extends AbstractTestKmsFacadeTest<Config, String, AwsKmsEdek> {

    AwsKmsTestKmsFacadeTest() {
        super(new AwsKmsTestKmsFacadeFactory());
    }

    @BeforeEach
    void beforeEach() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable()).withFailMessage("docker unavailable").isTrue();
    }

    @Test
    void classAndConfig() {
        try (var facade = factory.build()) {
            facade.start();
            assertThat(facade.getKmsServiceClass()).isEqualTo(AwsKmsService.class);
            assertThat(facade.getKmsServiceConfig()).isInstanceOf(Config.class);
        }
    }
}
