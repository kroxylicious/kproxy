/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Pod;
import io.strimzi.api.kafka.model.kafka.Kafka;

import io.kroxylicious.kms.service.TestKekManager;
import io.kroxylicious.kms.service.TestKmsFacade;
import io.kroxylicious.systemtests.clients.records.ConsumerRecord;
import io.kroxylicious.systemtests.extensions.KroxyliciousExtension;
import io.kroxylicious.systemtests.extensions.TestKubeKmsFacadeInvocationContextProvider;
import io.kroxylicious.systemtests.installation.kroxylicious.Kroxylicious;
import io.kroxylicious.systemtests.k8s.exception.KubeClusterException;
import io.kroxylicious.systemtests.resources.kms.ExperimentalKmsConfig;
import io.kroxylicious.systemtests.steps.KafkaSteps;
import io.kroxylicious.systemtests.steps.KroxyliciousSteps;
import io.kroxylicious.systemtests.templates.strimzi.KafkaNodePoolTemplates;
import io.kroxylicious.systemtests.templates.strimzi.KafkaTemplates;

import static io.kroxylicious.systemtests.k8s.KubeClusterResource.kubeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@ExtendWith(KroxyliciousExtension.class)
@ExtendWith(TestKubeKmsFacadeInvocationContextProvider.class)
class RecordEncryptionST extends AbstractST {
    protected static final String BROKER_NODE_NAME = "kafka";
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordEncryptionST.class);
    private static final String MESSAGE = "Hello-world";
    private final String clusterName = "my-cluster";
    private String bootstrap;
    private TestKekManager testKekManager;

    @BeforeAll
    void setUp() {
        List<Pod> kafkaPods = kubeClient().listPodsByPrefixInName(Constants.KAFKA_DEFAULT_NAMESPACE, clusterName);
        if (!kafkaPods.isEmpty()) {
            LOGGER.atInfo().setMessage("Skipping kafka deployment. It is already deployed!").log();
            return;
        }
        LOGGER.atInfo().setMessage("Deploying Kafka in {} namespace").addArgument(Constants.KAFKA_DEFAULT_NAMESPACE).log();

        Kafka kafka = KafkaTemplates.kafkaPersistentWithKRaftAnnotations(Constants.KAFKA_DEFAULT_NAMESPACE, clusterName, 3).build();

        resourceManager.createResourceWithWait(
                KafkaNodePoolTemplates.kafkaBasedNodePoolWithDualRole(BROKER_NODE_NAME, kafka, 3).build(),
                kafka);
    }

    @BeforeEach
    void beforeEach() {
        bootstrap = null;
        testKekManager = null;
    }

    @AfterEach
    void afterEach(String namespace) {
        try {
            if (testKekManager != null) {
                LOGGER.atInfo().log("Deleting KEK...");
                testKekManager.deleteKek("KEK_" + topicName);
            }
        }
        catch (KubeClusterException e) {
            LOGGER.atError().setMessage("KEK deletion has not been successfully done: {}").addArgument(e).log();
            throw e;
        }
        finally {
            if (bootstrap != null) {
                KafkaSteps.deleteTopic(namespace, topicName, bootstrap);
            }
        }
    }

    @TestTemplate
    void ensureClusterHasEncryptedMessage(String namespace, TestKmsFacade<?, ?, ?> testKmsFacade) {
        testKekManager = testKmsFacade.getTestKekManager();
        testKekManager.generateKek("KEK_" + topicName);
        int numberOfMessages = 1;

        // start Kroxylicious
        LOGGER.atInfo().setMessage("Given Kroxylicious in {} namespace with {} replicas").addArgument(namespace).addArgument(1).log();
        Kroxylicious kroxylicious = new Kroxylicious(namespace);
        kroxylicious.deployPortPerBrokerPlainWithRecordEncryptionFilter(clusterName, 1, testKmsFacade);
        bootstrap = kroxylicious.getBootstrap();

        LOGGER.atInfo().setMessage("And a kafka Topic named {}").addArgument(topicName).log();
        KafkaSteps.createTopic(namespace, topicName, bootstrap, 1, 2);

        LOGGER.atInfo().setMessage("When {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> resultEncrypted = KroxyliciousSteps.consumeMessageFromKafkaCluster(namespace, topicName, clusterName,
                Constants.KAFKA_DEFAULT_NAMESPACE, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(resultEncrypted).log();

        assertAll(
                () -> assertThat(resultEncrypted.stream())
                        .withFailMessage("expected header has not been received!")
                        .allMatch(r -> r.getRecordHeaders().containsKey("kroxylicious.io/encryption")),
                () -> assertThat(resultEncrypted.stream())
                        .withFailMessage("Encrypted message still includes the original one!")
                        .allMatch(r -> !r.getValue().contains(MESSAGE)));
    }

    @TestTemplate
    void produceAndConsumeMessage(String namespace, TestKmsFacade<?, ?, ?> testKmsFacade) {
        testKekManager = testKmsFacade.getTestKekManager();
        testKekManager.generateKek("KEK_" + topicName);
        int numberOfMessages = 1;

        // start Kroxylicious
        LOGGER.atInfo().setMessage("Given Kroxylicious in {} namespace with {} replicas").addArgument(namespace).addArgument(1).log();
        Kroxylicious kroxylicious = new Kroxylicious(namespace);
        kroxylicious.deployPortPerBrokerPlainWithRecordEncryptionFilter(clusterName, 1, testKmsFacade);
        bootstrap = kroxylicious.getBootstrap();

        LOGGER.atInfo().setMessage("And a kafka Topic named {}").addArgument(topicName).log();
        KafkaSteps.createTopic(namespace, topicName, bootstrap, 1, 2);

        LOGGER.atInfo().setMessage("When {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> result = KroxyliciousSteps.consumeMessages(namespace, topicName, bootstrap, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(result).log();

        assertThat(result).withFailMessage("expected messages have not been received!")
                .extracting(ConsumerRecord::getValue)
                .hasSize(numberOfMessages)
                .allSatisfy(v -> assertThat(v).contains(MESSAGE));
    }

    @TestTemplate
    void ensureClusterHasEncryptedMessageWithRotatedKEK(String namespace, TestKmsFacade<?, ?, ?> testKmsFacade) {
        // Skip AWS test execution because the ciphertext blob metadata to read the version of the KEK is not available anywhere
        assumeThat(testKmsFacade.getKmsServiceClass().getSimpleName().toLowerCase().contains("vault")).isTrue();
        testKekManager = testKmsFacade.getTestKekManager();
        testKekManager.generateKek("KEK_" + topicName);
        int numberOfMessages = 1;
        ExperimentalKmsConfig experimentalKmsConfig = new ExperimentalKmsConfig(1, 1, 1, 1);

        // start Kroxylicious
        LOGGER.atInfo().setMessage("Given Kroxylicious in {} namespace with {} replicas").addArgument(namespace).addArgument(1).log();
        Kroxylicious kroxylicious = new Kroxylicious(namespace);
        kroxylicious.deployPortPerBrokerPlainWithRecordEncryptionFilter(clusterName, 1, testKmsFacade, experimentalKmsConfig);
        bootstrap = kroxylicious.getBootstrap();

        LOGGER.atInfo().setMessage("And a kafka Topic named {}").addArgument(topicName).log();
        KafkaSteps.createTopic(namespace, topicName, bootstrap, 1, 2);

        LOGGER.atInfo().setMessage("When {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> resultEncrypted = KroxyliciousSteps.consumeMessageFromKafkaCluster(namespace, topicName, clusterName,
                Constants.KAFKA_DEFAULT_NAMESPACE, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(resultEncrypted).log();

        assertThat(resultEncrypted.stream())
                .withFailMessage("v1 is not contained in the ciphertext blob!")
                .allMatch(r -> r.getValue().contains("v1"));

        LOGGER.atInfo().setMessage("When KEK is rotated").log();
        testKekManager.rotateKek("KEK_" + topicName);

        LOGGER.atInfo().setMessage("And {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> resultEncryptedRotatedKek = KroxyliciousSteps.consumeMessageFromKafkaCluster(namespace, topicName, clusterName,
                Constants.KAFKA_DEFAULT_NAMESPACE, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(resultEncryptedRotatedKek).log();

        assertThat(resultEncryptedRotatedKek.stream())
                .withFailMessage("v2 is not contained in the ciphertext blob!")
                .anyMatch(r -> r.getValue().contains("v2"));
    }

    @TestTemplate
    void produceAndConsumeMessageWithRotatedKEK(String namespace, TestKmsFacade<?, ?, ?> testKmsFacade) {
        testKekManager = testKmsFacade.getTestKekManager();
        testKekManager.generateKek("KEK_" + topicName);
        int numberOfMessages = 1;
        ExperimentalKmsConfig experimentalKmsConfig = new ExperimentalKmsConfig(1, 1, 1, 1);

        // start Kroxylicious
        LOGGER.atInfo().setMessage("Given Kroxylicious in {} namespace with {} replicas").addArgument(namespace).addArgument(1).log();
        Kroxylicious kroxylicious = new Kroxylicious(namespace);
        kroxylicious.deployPortPerBrokerPlainWithRecordEncryptionFilter(clusterName, 1, testKmsFacade, experimentalKmsConfig);
        bootstrap = kroxylicious.getBootstrap();

        LOGGER.atInfo().setMessage("And a kafka Topic named {}").addArgument(topicName).log();
        KafkaSteps.createTopic(namespace, topicName, bootstrap, 1, 2);

        LOGGER.atInfo().setMessage("When {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> result = KroxyliciousSteps.consumeMessages(namespace, topicName, bootstrap, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(result).log();

        assertThat(result).withFailMessage("expected messages have not been received!")
                .extracting(ConsumerRecord::getValue)
                .hasSize(numberOfMessages)
                .allSatisfy(v -> assertThat(v).contains(MESSAGE));

        LOGGER.atInfo().setMessage("When KEK is rotated").log();
        testKekManager.rotateKek("KEK_" + topicName);

        LOGGER.atInfo().setMessage("And {} messages '{}' are sent to the topic '{}'").addArgument(numberOfMessages).addArgument(MESSAGE).addArgument(topicName).log();
        KroxyliciousSteps.produceMessages(namespace, topicName, bootstrap, MESSAGE, numberOfMessages);

        LOGGER.atInfo().setMessage("Then the messages are consumed").log();
        List<ConsumerRecord> resultRotatedKek = KroxyliciousSteps.consumeMessages(namespace, topicName, bootstrap, numberOfMessages, Duration.ofMinutes(2));
        LOGGER.atInfo().setMessage("Received: {}").addArgument(resultRotatedKek).log();

        assertThat(resultRotatedKek).withFailMessage("expected messages have not been received!")
                .extracting(ConsumerRecord::getValue)
                .allSatisfy(v -> assertThat(v).contains(MESSAGE));
    }
}
