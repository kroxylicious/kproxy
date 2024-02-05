/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import io.kroxylicious.kms.provider.hashicorp.vault.VaultResponse.ReadKeyData;
import io.kroxylicious.kms.provider.hashicorp.vault.config.Config;
import io.kroxylicious.proxy.config.tls.FilePassword;
import io.kroxylicious.proxy.config.tls.InlinePassword;
import io.kroxylicious.proxy.config.tls.InsecureTls;
import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.config.tls.TrustStore;

import edu.umd.cs.findbugs.annotations.NonNull;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration tests for HashiCorp Vault with TLS, checking that we can make
 * API calls with TLS enabled on the server.
 */
class VaultKmsTlsIT {

    private static final String VAULT_TOKEN = "token";
    private TestVault vaultContainer;

    private static final CertificateGenerator.Keys keys = CertificateGenerator.generate();

    @BeforeEach
    void beforeEach() {
        assumeThat(DockerClientFactory.instance().isDockerAvailable()).withFailMessage("docker unavailable").isTrue();
        vaultContainer = TestVault.startWithTls(keys);
    }

    @AfterEach
    void afterEach() {
        if (vaultContainer != null) {
            vaultContainer.close();
        }
    }

    interface KmsCreator {
        VaultKms createKms(URI endpoint);
    }

    static List<Arguments> tlsConfigurations() {
        return List.of(
                Arguments.of("pkcs12Tls", (KmsCreator) (uri) -> getTlsVaultKms(tlsForTrustStoreInlinePassword(keys.pkcs12ClientTruststore()), uri)),
                Arguments.of("pkcs12NoPasswordTlsService",
                        (KmsCreator) (uri) -> getTlsVaultKms(tlsForTrustStoreNoPassword(keys.pkcs12NoPasswordClientTruststore()), uri)),
                Arguments.of("filePasswordTls", (KmsCreator) (uri) -> getTlsVaultKms(tlsForTrustStoreFilePassword(keys.pkcs12ClientTruststore()), uri)),
                Arguments.of("jksTls", (KmsCreator) (uri) -> getTlsVaultKms(tlsForTrustStoreInlinePassword(keys.jksClientTruststore()), uri)),
                Arguments.of("defaultStoreTypeTls", (KmsCreator) (uri) -> getTlsVaultKms(defaultStoreTypeTls(keys.jksClientTruststore()), uri)),
                Arguments.of("tlsInsecure", (KmsCreator) (uri) -> getTlsVaultKms(insecureTls(), uri)),
                Arguments.of("pkcs12Tls", (KmsCreator) (uri) -> getTlsVaultKms(tlsForTrustStoreInlinePassword(keys.pkcs12ClientTruststore()), uri)));
    }

    @Test
    void tlsConnectionFailsWithoutClientTrust() {
        var tlsConfig = vaultConfig(null, vaultContainer.getEndpoint());
        var keyName = "mykey";
        createKek(keyName);
        VaultKms service = new VaultKmsService().buildKms(tlsConfig);
        var resolved = service.resolveAlias(keyName);
        assertThat(resolved)
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat().havingCause()
                .isInstanceOf(SSLHandshakeException.class)
                .withMessageContaining("unable to find valid certification path to requested target");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tlsConfigurations")
    void testArbitraryKmsOperationSucceedsWithTls(String kms, KmsCreator creator) {
        VaultKms service = creator.createKms(vaultContainer.getEndpoint());
        var keyName = "mykey";
        createKek(keyName);
        var resolved = service.resolveAlias(keyName);
        assertThat(resolved)
                .succeedsWithin(Duration.ofSeconds(5))
                .isEqualTo(keyName);
    }

    private ReadKeyData createKek(String keyId) {
        return vaultContainer.runVaultCommand(new TypeReference<>() {
        }, "vault", "write", "-f", "transit/keys/%s".formatted(keyId));
    }

    @NonNull
    private static Tls insecureTls() {
        return new Tls(null, new InsecureTls(true));
    }

    @NonNull
    private static Tls defaultStoreTypeTls(CertificateGenerator.TrustStore jksTrustStore) {
        return new Tls(null, new TrustStore(jksTrustStore.path().toString(), new InlinePassword(jksTrustStore.password()), null));
    }

    @NonNull
    private static VaultKms getTlsVaultKms(Tls tls, URI endpoint) {
        return new VaultKmsService().buildKms(vaultConfig(tls, endpoint));
    }

    @NonNull
    private static Config vaultConfig(Tls tls, URI endpoint) {
        return new Config(endpoint, VAULT_TOKEN, tls);
    }

    @NonNull
    private static Tls tlsForTrustStoreInlinePassword(CertificateGenerator.TrustStore trustStore) {
        return new Tls(null, new TrustStore(trustStore.path().toString(), new InlinePassword(trustStore.password()), trustStore.type()));
    }

    @NonNull
    private static Tls tlsForTrustStoreFilePassword(CertificateGenerator.TrustStore trustStore) {
        return new Tls(null, new TrustStore(trustStore.path().toString(), new FilePassword(trustStore.passwordFile().toString()), trustStore.type()));
    }

    @NonNull
    private static Tls tlsForTrustStoreNoPassword(CertificateGenerator.TrustStore trustStore) {
        return new Tls(null, new TrustStore(trustStore.path().toString(), null, trustStore.type()));
    }

}
