/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.test.tester;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.message.DescribeAclsRequestData;
import org.apache.kafka.common.message.DescribeAclsResponseData;
import org.apache.kafka.common.message.ListTransactionsRequestData;
import org.apache.kafka.common.message.ListTransactionsResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.kroxylicious.proxy.config.ClusterNetworkAddressConfigProviderDefinitionBuilder;
import io.kroxylicious.proxy.config.ConfigurationBuilder;
import io.kroxylicious.proxy.config.VirtualClusterBuilder;
import io.kroxylicious.test.Request;
import io.kroxylicious.test.Response;
import io.kroxylicious.test.client.KafkaClient;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.test.tester.KroxyliciousConfigUtils.DEFAULT_VIRTUAL_CLUSTER;
import static io.kroxylicious.test.tester.KroxyliciousConfigUtils.proxy;
import static io.kroxylicious.test.tester.KroxyliciousTesters.kroxyliciousTester;
import static io.kroxylicious.test.tester.KroxyliciousTesters.mockKafkaKroxyliciousTester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(KafkaClusterExtension.class)
class KroxyliciousTestersTest {

    public static final String TOPIC = "example";

    @Test
    void testAdminMethods(KafkaCluster cluster) throws Exception {
        try (var tester = kroxyliciousTester(proxy(cluster))) {
            assertNotNull(tester.admin().describeCluster().clusterId().get(10, TimeUnit.SECONDS));
            assertNotNull(tester.admin(Map.of()).describeCluster().clusterId().get(10, TimeUnit.SECONDS));
            assertNotNull(tester.admin(DEFAULT_VIRTUAL_CLUSTER, Map.of()).describeCluster().clusterId().get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void testConsumerMethods(KafkaCluster cluster) throws Exception {
        KafkaProducer<String, String> producer = new KafkaProducer<>(cluster.getKafkaClientConfiguration(), new StringSerializer(), new StringSerializer());
        producer.send(new ProducerRecord<>(TOPIC, "key", "value")).get(10, TimeUnit.SECONDS);
        try (var tester = kroxyliciousTester(proxy(cluster))) {
            withConsumer(tester::consumer, KroxyliciousTestersTest::assertOneRecordConsumedFrom);
            withConsumer(() -> tester.consumer(randomGroupIdAndEarliestReset()), KroxyliciousTestersTest::assertOneRecordConsumedFrom);
            withConsumer(() -> tester.consumer(Serdes.String(), Serdes.String(), randomGroupIdAndEarliestReset()), KroxyliciousTestersTest::assertOneRecordConsumedFrom);
            withConsumer(() -> tester.consumer(DEFAULT_VIRTUAL_CLUSTER), KroxyliciousTestersTest::assertOneRecordConsumedFrom);
            withConsumer(() -> tester.consumer(DEFAULT_VIRTUAL_CLUSTER, randomGroupIdAndEarliestReset()), KroxyliciousTestersTest::assertOneRecordConsumedFrom);
            withConsumer(() -> tester.consumer(DEFAULT_VIRTUAL_CLUSTER, Serdes.String(), Serdes.String(), randomGroupIdAndEarliestReset()),
                    KroxyliciousTestersTest::assertOneRecordConsumedFrom);
        }
    }

    private void withConsumer(Supplier<Consumer<String, String>> supplier, java.util.function.Consumer<Consumer<String, String>> consumerFunc) {
        try (Consumer<String, String> consumer = supplier.get()) {
            consumerFunc.accept(consumer);
        }
    }

    @Test
    void testProducerMethods(KafkaCluster cluster) throws Exception {
        try (var tester = kroxyliciousTester(proxy(cluster))) {
            send(tester.producer());
            send(tester.producer(Map.of()));
            send(tester.producer(Serdes.String(), Serdes.String(), Map.of()));
            send(tester.producer(DEFAULT_VIRTUAL_CLUSTER));
            send(tester.producer(DEFAULT_VIRTUAL_CLUSTER, Map.of()));
            send(tester.producer(DEFAULT_VIRTUAL_CLUSTER, Serdes.String(), Serdes.String(), Map.of()));

            HashMap<String, Object> config = new HashMap<>(cluster.getKafkaClientConfiguration());
            config.putAll(randomGroupIdAndEarliestReset());
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer());
            consumer.subscribe(List.of(TOPIC));
            int recordCount = consumer.poll(Duration.ofSeconds(10)).count();
            assertEquals(6, recordCount);
        }
    }

    @Test
    void testMockRequestMockTester() {
        try (var tester = mockKafkaKroxyliciousTester(KroxyliciousConfigUtils::proxy)) {
            assertCanSendRequestsAndReceiveMockResponses(tester, tester::mockRequestClient);
            assertCanSendRequestsAndReceiveMockResponses(tester, () -> tester.mockRequestClient(DEFAULT_VIRTUAL_CLUSTER));
        }
    }

    @Test
    void testMockRequestClientReportsConnectionState() {
        try (var tester = mockKafkaKroxyliciousTester(KroxyliciousConfigUtils::proxy);
                var kafkaClient = tester.mockRequestClient()) {
            assertThat(kafkaClient.isOpen()).isFalse();

            var mockResponse = new DescribeAclsResponseData().setErrorMessage("hello").setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code());
            tester.addMockResponseForApiKey(new Response(ApiKeys.DESCRIBE_ACLS, ApiKeys.DESCRIBE_ACLS.latestVersion(), mockResponse));

            var response = kafkaClient
                    .getSync(new Request(ApiKeys.DESCRIBE_ACLS, ApiKeys.DESCRIBE_ACLS.latestVersion(), "client", new DescribeAclsRequestData()));
            assertThat(response.message()).isEqualTo(mockResponse);
            assertThat(kafkaClient.isOpen()).isTrue();
        }
    }

    @Test
    void testIllegalToAskForNonExistentVirtualCluster(KafkaCluster cluster) {
        try (var tester = kroxyliciousTester(proxy(cluster))) {
            assertThrows(IllegalArgumentException.class, () -> tester.mockRequestClient("NON_EXIST"));
            assertThrows(IllegalArgumentException.class, () -> tester.consumer("NON_EXIST"));
            assertThrows(IllegalArgumentException.class, () -> tester.consumer("NON_EXIST", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> tester.consumer("NON_EXIST", Serdes.String(), Serdes.String(), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> tester.producer("NON_EXIST"));
            assertThrows(IllegalArgumentException.class, () -> tester.producer("NON_EXIST", Map.of()));
            assertThrows(IllegalArgumentException.class, () -> tester.producer("NON_EXIST", Serdes.String(), Serdes.String(), Map.of()));
            assertThrows(IllegalArgumentException.class, () -> tester.admin("NON_EXIST"));
            assertThrows(IllegalArgumentException.class, () -> tester.admin("NON_EXIST", Map.of()));
        }
    }

    @Test
    void testIllegalToAskForDefaultClientsWhenVirtualClustersAmbiguous(KafkaCluster cluster) {
        String clusterBootstrapServers = cluster.getBootstrapServers();
        ConfigurationBuilder builder = new ConfigurationBuilder();
        ConfigurationBuilder proxy = addVirtualCluster(clusterBootstrapServers, addVirtualCluster(clusterBootstrapServers, builder, "foo",
                "localhost:9192"), "bar", "localhost:9296");
        try (var tester = kroxyliciousTester(proxy)) {
            assertThrows(AmbiguousVirtualClusterException.class, tester::mockRequestClient);
            assertThrows(AmbiguousVirtualClusterException.class, tester::consumer);
            assertThrows(AmbiguousVirtualClusterException.class, () -> tester.consumer(Map.of()));
            assertThrows(AmbiguousVirtualClusterException.class, () -> tester.consumer(Serdes.String(), Serdes.String(), Map.of()));
            assertThrows(AmbiguousVirtualClusterException.class, tester::producer);
            assertThrows(AmbiguousVirtualClusterException.class, () -> tester.producer(Map.of()));
            assertThrows(AmbiguousVirtualClusterException.class, () -> tester.producer(Serdes.String(), Serdes.String(), Map.of()));
            assertThrows(AmbiguousVirtualClusterException.class, tester::admin);
            assertThrows(AmbiguousVirtualClusterException.class, () -> tester.admin(Map.of()));
        }
    }

    private static ConfigurationBuilder addVirtualCluster(String clusterBootstrapServers, ConfigurationBuilder builder, String clusterName,
                                                          String defaultProxyBootstrap) {
        return builder.addToVirtualClusters(clusterName, new VirtualClusterBuilder()
                .withNewTargetCluster()
                .withBootstrapServers(clusterBootstrapServers)
                .endTargetCluster()
                .withClusterNetworkAddressConfigProvider(
                        new ClusterNetworkAddressConfigProviderDefinitionBuilder("PortPerBroker").withConfig("bootstrapAddress", defaultProxyBootstrap)
                                .build())
                .build());
    }

    private static void assertCanSendRequestsAndReceiveMockResponses(MockServerKroxyliciousTester tester, Supplier<KafkaClient> kafkaClientSupplier) {
        try (var kafkaClient = kafkaClientSupplier.get()) {
            var mockResponse1 = new DescribeAclsResponseData().setErrorMessage("hello").setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code());
            tester.addMockResponseForApiKey(new Response(ApiKeys.DESCRIBE_ACLS, ApiKeys.DESCRIBE_ACLS.latestVersion(), mockResponse1));

            var mockResponse2 = new ListTransactionsResponseData().setErrorCode(Errors.UNKNOWN_SERVER_ERROR.code());
            tester.addMockResponseForApiKey(new Response(ApiKeys.LIST_TRANSACTIONS, ApiKeys.LIST_TRANSACTIONS.latestVersion(), mockResponse2));

            var response1 = kafkaClient
                    .getSync(new Request(ApiKeys.DESCRIBE_ACLS, ApiKeys.DESCRIBE_ACLS.latestVersion(), "client", new DescribeAclsRequestData()));
            assertThat(response1.message()).isInstanceOf(DescribeAclsResponseData.class);
            assertThat(response1.message()).isEqualTo(mockResponse1);

            var response2 = kafkaClient
                    .getSync(new Request(ApiKeys.LIST_TRANSACTIONS, ApiKeys.LIST_TRANSACTIONS.latestVersion(), "client", new ListTransactionsRequestData()));
            assertInstanceOf(ListTransactionsResponseData.class, response2.message());
            assertThat(response2.message()).isInstanceOf(ListTransactionsResponseData.class);
            assertThat(response2.message()).isEqualTo(mockResponse2);
        }
    }

    private static void send(Producer<String, String> producer) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, "key", "value")).get(10, TimeUnit.SECONDS);
    }

    @NotNull
    private static Map<String, Object> randomGroupIdAndEarliestReset() {
        return Map.of(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    private static void assertOneRecordConsumedFrom(Consumer<String, String> consumer) {
        consumer.subscribe(List.of(TOPIC));
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
        assertEquals(1, records.count());
    }

}
