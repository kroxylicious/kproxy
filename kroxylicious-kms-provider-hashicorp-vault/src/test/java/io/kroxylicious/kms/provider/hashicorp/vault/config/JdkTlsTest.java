/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.kms.provider.hashicorp.vault.config;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;

import io.kroxylicious.kms.provider.hashicorp.vault.CertificateGenerator;
import io.kroxylicious.proxy.config.tls.InlinePassword;
import io.kroxylicious.proxy.config.tls.InsecureTls;
import io.kroxylicious.proxy.config.tls.Tls;
import io.kroxylicious.proxy.config.tls.TrustStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkTlsTest {
    public static final X509Certificate SELF_SIGNED_X_509_CERTIFICATE = CertificateGenerator.generateSelfSignedX509Certificate(CertificateGenerator.generateRsaKeyPair());

    @Test
    void testInsecureTlsEnabled() {
        InsecureTls insecureTls = new InsecureTls(true);
        TrustManager[] trustManagers = JdkTls.getTrustManagers(insecureTls);
        for (TrustManager trustManager : trustManagers) {
            assertThat(trustManager).isInstanceOfSatisfying(X509TrustManager.class, x509TrustManager -> {
                assertThat(x509TrustManager.getAcceptedIssuers()).isNotNull().isEmpty();
                assertThatCode(() -> x509TrustManager.checkClientTrusted(new X509Certificate[]{ SELF_SIGNED_X_509_CERTIFICATE }, "any")).doesNotThrowAnyException();
                assertThatCode(() -> x509TrustManager.checkServerTrusted(new X509Certificate[]{ SELF_SIGNED_X_509_CERTIFICATE }, "any")).doesNotThrowAnyException();
            });
        }
    }

    @Test
    void testInsecureTlsDisabled() {
        InsecureTls insecureTlsDisabled = new InsecureTls(false);
        TrustManager[] trustManagers = JdkTls.getTrustManagers(insecureTlsDisabled);
        for (TrustManager trustManager : trustManagers) {
            assertThat(trustManager).isInstanceOfSatisfying(X509TrustManager.class, x509TrustManager -> {
                assertThat(x509TrustManager.getAcceptedIssuers()).isNotNull().isNotEmpty();
                assertThatThrownBy(() -> x509TrustManager.checkClientTrusted(new X509Certificate[]{ SELF_SIGNED_X_509_CERTIFICATE }, "any")).isInstanceOf(
                        CertificateException.class);
                assertThatThrownBy(() -> x509TrustManager.checkServerTrusted(new X509Certificate[]{ SELF_SIGNED_X_509_CERTIFICATE }, "any")).isInstanceOf(
                        CertificateException.class);
            });
        }
    }

    @Test
    void testNullTrustManagersResultsInDefaultSslContext() throws NoSuchAlgorithmException {
        JdkTls tls = new JdkTls(new Tls(null, null));
        SSLContext sslContext = tls.sslContext();
        assertThat(sslContext).isSameAs(SSLContext.getDefault());
    }

    @Test
    void testSslContextProtocolIsTlsIfWeSupplyTrust() {
        JdkTls tls = new JdkTls(new Tls(null, new InsecureTls(true)));
        SSLContext sslContext = tls.sslContext();
        assertThat(sslContext.getProtocol()).isEqualTo("TLS");
    }

    @Test
    void testNullTlsResultsInDefaultSslContext() throws NoSuchAlgorithmException {
        JdkTls tls = new JdkTls(null);
        SSLContext sslContext = tls.sslContext();
        assertThat(sslContext).isSameAs(SSLContext.getDefault());
    }

    @Test
    void testFileNotFound() {
        TrustStore store = new TrustStore("/tmp/" + UUID.randomUUID(), new InlinePassword("changeit"), null);
        assertThatThrownBy(() -> JdkTls.getTrustManagers(store)).isInstanceOf(SslConfigurationException.class).cause().isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void testJks() {
        CertificateGenerator.Keys keys = CertificateGenerator.generate();
        CertificateGenerator.TrustStore trustStore = keys.jksClientTruststore();
        TrustStore store = new TrustStore(trustStore.path().toString(), new InlinePassword(trustStore.password()), null);
        TrustManager[] trustManagers = JdkTls.getTrustManagers(store);
        assertThat(trustManagers).isNotEmpty();
    }

    @Test
    void testPemNotSupported() {
        TrustStore store = new TrustStore("/tmp/store", null, "PEM");
        assertThatThrownBy(() -> {
            JdkTls.getTrustManagers(store);
        }).isInstanceOf(SslConfigurationException.class).hasMessage("PEM trust not supported by vault yet");
    }

    @Test
    void testJksWrongPassword() {
        CertificateGenerator.Keys keys = CertificateGenerator.generate();
        CertificateGenerator.TrustStore trustStore = keys.jksClientTruststore();
        String badPassword = UUID.randomUUID().toString();
        TrustStore store = new TrustStore(trustStore.path().toString(), new InlinePassword(badPassword), null);
        assertThatThrownBy(() -> JdkTls.getTrustManagers(store)).isInstanceOf(SslConfigurationException.class).cause().isInstanceOf(IOException.class)
                .hasMessageContaining("Keystore was tampered with, or password was incorrect");
    }

    @Test
    void testPkcs12() {
        CertificateGenerator.Keys keys = CertificateGenerator.generate();
        CertificateGenerator.TrustStore trustStore = keys.pkcs12ClientTruststore();
        TrustStore store = new TrustStore(trustStore.path().toString(), new InlinePassword(trustStore.password()), "PKCS12");
        TrustManager[] trustManagers = JdkTls.getTrustManagers(store);
        assertThat(trustManagers).isNotEmpty();
    }

    @Test
    void testPkcs12WrongPassword() {
        CertificateGenerator.Keys keys = CertificateGenerator.generate();
        CertificateGenerator.TrustStore trustStore = keys.pkcs12ClientTruststore();
        String badPassword = UUID.randomUUID().toString();
        TrustStore store = new TrustStore(trustStore.path().toString(), new InlinePassword(badPassword), null);
        assertThatThrownBy(() -> JdkTls.getTrustManagers(store)).isInstanceOf(SslConfigurationException.class).cause().isInstanceOf(IOException.class)
                .hasMessageContaining("keystore password was incorrect");
    }

}
