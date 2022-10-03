/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.testcluster.Cluster;
import io.kroxylicious.proxy.testcluster.ClusterConfig;
import io.kroxylicious.proxy.testcluster.ClusterFactory;
import io.kroxylicious.proxy.testcluster.ContainerBasedKafkaCluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test case that simply exercises the ability to control the kafka cluster from the test.
 *
 */
public class ClusterIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterIT.class);

    @Test
    public void kafkaClusterKraftMode() throws Exception {
        try (var cluster = ClusterFactory.create(ClusterConfig.builder().kraftMode(true).build())) {
            cluster.start();
            verify(1, cluster);
        }
    }

    @Test
    public void kafkaClusterZookeeperMode() throws Exception {
        try (var cluster = ClusterFactory.create(ClusterConfig.builder().kraftMode(false).build())) {
            cluster.start();
            verify(1, cluster);
        }
    }

    @Test
    public void kafkaMultiNodeClusterKraftMode() throws Exception {
        int brokersNum = 2;
        try (var cluster = ClusterFactory.create(ClusterConfig.builder().brokersNum(brokersNum).kraftMode(true).build())) {
            assumeTrue(cluster instanceof ContainerBasedKafkaCluster, "FIXME: kraft timing out on shutdown in multinode case");
            cluster.start();
            verify(brokersNum, cluster);
        }
    }

    @Test
    public void kafkaMultiNodeClusterZookeeperMode() throws Exception {
        int brokersNum = 2;
        try (var cluster = ClusterFactory.create(ClusterConfig.builder().brokersNum(brokersNum).kraftMode(false).build())) {
            cluster.start();
            verify(brokersNum, cluster);
        }
    }

    @Test
    public void kafkaClusterKraftModeWithAuth() throws Exception {
        try (var cluster = ClusterFactory.create(
                ClusterConfig.builder().kraftMode(true).saslMechanism("PLAIN").user("guest", "guest").build())) {
            cluster.start();
            verify(1, cluster);
        }
    }

    @Test
    public void kafkaClusterZookeeperModeWithAuth() throws Exception {
        try (var cluster = ClusterFactory.create(
                ClusterConfig.builder().kraftMode(false).saslMechanism("PLAIN").user("guest", "guest").build())) {
            cluster.start();
            verify(1, cluster);
        }
    }

    private void verify(int expected, Cluster cluster) throws Exception {
        var topic = "TOPIC_1";
        var message = "Hello, world!";

        try (var admin = KafkaAdminClient.create(cluster.getConnectConfigForCluster())) {
            assertEquals(expected, getActualNumberOfBrokers(admin));
            var rf = (short) Math.min(1, Math.max(expected, 3));
            createTopic(admin, topic, rf);
        }

        produce(cluster, topic, message);
        consume(cluster, topic, "Hello, world!");
    }

    private void produce(Cluster cluster, String topic, String message) throws Exception {
        Map<String, Object> config = cluster.getConnectConfigForCluster();
        config.putAll(Map.<String, Object> of(
                ProducerConfig.CLIENT_ID_CONFIG, "myclient",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000));
        try (var producer = new KafkaProducer<String, String>(config)) {
            producer.send(new ProducerRecord<>(topic, "my-key", message)).get();
        }
    }

    private void consume(Cluster cluster, String topic, String message) throws Exception {
        Map<String, Object> config = cluster.getConnectConfigForCluster();
        config.putAll(Map.of(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.GROUP_ID_CONFIG, "my-group-id",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"));
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(Set.of(topic));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            assertEquals(message, records.iterator().next().value());
        }
    }

    private int getActualNumberOfBrokers(AdminClient admin) throws Exception {
        DescribeClusterResult describeClusterResult = admin.describeCluster();
        return describeClusterResult.nodes().get().size();
    }

    private void createTopic(AdminClient admin1, String topic, short replicationFactor) throws Exception {
        admin1.createTopics(List.of(new NewTopic(topic, 1, replicationFactor))).all().get();
    }

    @BeforeEach
    void before(TestInfo testInfo) {
        LOGGER.warn("Running {}", testInfo.getTestMethod().get().getName());
    }

    @AfterEach
    void after(TestInfo testInfo) {
        LOGGER.warn("Done running {}", testInfo.getTestMethod().get().getName());
    }

}
