/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.multitenant;

import java.time.Duration;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DeleteTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicCollection;
import org.apache.kafka.common.TopicCollection.TopicNameCollection;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.assertj.core.api.Condition;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.KafkaProxy;
import io.kroxylicious.proxy.config.ConfigParser;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.testkafkacluster.KafkaCluster;
import io.kroxylicious.proxy.testkafkacluster.KafkaClusterConfig;
import io.kroxylicious.proxy.testkafkacluster.KafkaClusterFactory;
import io.kroxylicious.proxy.testkafkacluster.KeytoolCertificateGenerator;

import static org.assertj.core.api.Assertions.allOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MultiTenantIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiTenantIT.class);

    private static final String TOPIC_1 = "my-test-topic";
    private static final NewTopic NEW_TOPIC_1 = new NewTopic(TOPIC_1, 1, (short) 1);
    private static final String TOPIC_2 = "other-test-topic";
    private static final NewTopic NEW_TOPIC_2 = new NewTopic(TOPIC_2, 1, (short) 1);

    private static final String TOPIC_3 = "and-another-test-topic";
    private static final NewTopic NEW_TOPIC_3 = new NewTopic(TOPIC_3, 1, (short) 1);

    private static final String PROXY_ADDRESS = "localhost:9192";
    private static final String TENANT1_PROXY_ADDRESS = "foo.multitenant.kafka:9192";
    private static final String TENANT2_PROXY_ADDRESS = "bar.multitenant.kafka:9192";
    private static final String MY_KEY = "my-key";
    private static final String MY_VALUE = "my-value";
    private TestInfo testInfo;
    private KeytoolCertificateGenerator certificateGenerator;

    @BeforeEach
    public void beforeEach(TestInfo testInfo) throws Exception {
        this.testInfo = testInfo;
        this.certificateGenerator = new KeytoolCertificateGenerator();
        this.certificateGenerator.generateSelfSignedCertificateEntry("test@redhat.com", "*.multitenant.kafka", "KI", "RedHat", null, null, "US");
    }

    @Test
    public void createAndDeleteTopic() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                try (var admin = Admin.create(commonConfig(TENANT1_PROXY_ADDRESS, Map.of()))) {
                    var created = createTopics(admin, List.of(NEW_TOPIC_1, NEW_TOPIC_2));

                    ListTopicsResult listTopicsResult = admin.listTopics();
                    var topicMap = listTopicsResult.namesToListings().get();
                    assertEquals(2, topicMap.size());
                    assertThat(topicMap).hasSize(2);
                    assertThat(topicMap).hasEntrySatisfying(TOPIC_1,
                            allOf(matches(TopicListing::name, TOPIC_1),
                                    matches(TopicListing::topicId, created.topicId(TOPIC_1).get())));
                    assertThat(topicMap).hasEntrySatisfying(TOPIC_1,
                            allOf(matches(TopicListing::name, TOPIC_1),
                                    matches(TopicListing::topicId, created.topicId(TOPIC_1).get())));
                    assertThat(topicMap).hasEntrySatisfying(TOPIC_2, matches(TopicListing::name, TOPIC_2));

                    // Delete by name
                    var topics1 = TopicNameCollection.ofTopicNames(List.of(TOPIC_1));
                    var deleted = deleteTopics(admin, topics1);
                    assertThat(deleted.topicNameValues().keySet()).containsAll(topics1.topicNames());

                    // Delete by id
                    var topics2 = TopicCollection.ofTopicIds(List.of(created.topicId(TOPIC_2).get()));
                    deleted = deleteTopics(admin, topics2);
                    assertThat(deleted.topicIdValues().keySet()).containsAll(topics2.topicIds());
                }
            }
        }
    }

    @Test
    public void describeTopic() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                try (var admin = Admin.create(commonConfig(TENANT1_PROXY_ADDRESS, Map.of()))) {
                    var created = createTopics(admin, List.of(NEW_TOPIC_1));

                    var describeTopicsResult = admin.describeTopics(TopicNameCollection.ofTopicNames(List.of(TOPIC_1)));
                    var topicMap = describeTopicsResult.allTopicNames().get();
                    assertThat(topicMap).hasEntrySatisfying(TOPIC_1,
                            allOf(matches(TopicDescription::name, TOPIC_1),
                                    matches(TopicDescription::topicId, created.topicId(TOPIC_1).get())));
                }
            }
        }
    }

    @Test
    public void publishOne() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                createTopics(TENANT1_PROXY_ADDRESS, List.of(NEW_TOPIC_1));
                produceAndVerify(TENANT1_PROXY_ADDRESS, TOPIC_1, MY_KEY, MY_VALUE);
            }
        }
    }

    @Test
    public void consumeOne() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();

            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                createTopics(TENANT1_PROXY_ADDRESS, List.of(NEW_TOPIC_1));
                produceAndVerify(TENANT1_PROXY_ADDRESS, TOPIC_1, MY_KEY, MY_VALUE);
                consumeAndVerify(TENANT1_PROXY_ADDRESS, TOPIC_1, MY_KEY, MY_VALUE, false);
            }
        }
    }

    @Test
    public void consumeOneAndOffsetCommit() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();
            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                createTopics(TENANT1_PROXY_ADDRESS, List.of(NEW_TOPIC_1));
                produceAndVerify(TENANT1_PROXY_ADDRESS,
                        Stream.of(new ProducerRecord<>(TOPIC_1, MY_KEY, MY_VALUE + "1"), new ProducerRecord<>(TOPIC_1, MY_KEY, MY_VALUE + "2")));
                consumeAndVerify(TENANT1_PROXY_ADDRESS, TOPIC_1, MY_KEY, MY_VALUE + "1", true);
                consumeAndVerify(TENANT1_PROXY_ADDRESS, TOPIC_1, MY_KEY, MY_VALUE + "2", true);
            }
        }
    }

    @Test
    public void tenantIsolation() throws Exception {
        try (var cluster = KafkaClusterFactory.create(KafkaClusterConfig.builder().testInfo(testInfo).build())) {
            cluster.start();
            String config = getConfig(PROXY_ADDRESS, cluster);

            try (var proxy = startProxy(config)) {
                createTopics(TENANT1_PROXY_ADDRESS, List.of(NEW_TOPIC_1));
                createTopics(TENANT2_PROXY_ADDRESS, List.of(NEW_TOPIC_2, NEW_TOPIC_3));

                verifyTenant(TENANT1_PROXY_ADDRESS, TOPIC_1);
                verifyTenant(TENANT2_PROXY_ADDRESS, TOPIC_2, TOPIC_3);
            }
        }
    }

    private void verifyTenant(String address, String... expectedTopics) throws Exception {
        try (var admin = Admin.create(commonConfig(address, Map.of()))) {

            var listTopicsResult = admin.listTopics();

            var topicListMap = listTopicsResult.namesToListings().get();
            assertEquals(expectedTopics.length, topicListMap.size());
            Arrays.stream(expectedTopics).forEach(
                    expectedTopic -> assertThat(topicListMap).hasEntrySatisfying(expectedTopic,
                            allOf(matches(TopicListing::name, expectedTopic))));

            var describeTopicsResult = admin.describeTopics(TopicNameCollection.ofTopicNames(Arrays.stream(expectedTopics).toList()));
            var topicDescribeMap = describeTopicsResult.allTopicNames().get();
            assertEquals(expectedTopics.length, topicDescribeMap.size());
            Arrays.stream(expectedTopics).forEach(expectedTopic -> assertThat(topicDescribeMap).hasEntrySatisfying(expectedTopic,
                    allOf(matches(TopicDescription::name, expectedTopic))));
        }
    }

    private void consumeAndVerify(String address, String topicName, String expectedKey, String expectedValue, boolean offsetCommit) {
        consumeAndVerify(address, topicName, new LinkedList<>(List.of(matchesRecord(topicName, expectedKey, expectedValue))), offsetCommit);
    }

    private void consumeAndVerify(String address, String topicName, Deque<Predicate<ConsumerRecord<String, String>>> expected, boolean offsetCommit) {
        try (var consumer = new KafkaConsumer<String, String>(commonConfig(address, Map.of(
                ConsumerConfig.GROUP_ID_CONFIG, testInfo.getDisplayName(),
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, Boolean.FALSE.toString(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE.toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, OffsetResetStrategy.EARLIEST.toString(),
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.format("%d", expected.size()),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)))) {

            var topicPartitions = List.of(new TopicPartition(topicName, 0));
            consumer.assign(topicPartitions);

            while (!expected.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(30));
                assertThat(records.partitions()).hasSizeGreaterThanOrEqualTo(1);
                records.forEach(r -> {
                    assertFalse(expected.isEmpty(), String.format("received unexpected record %s", r));
                    assertThat(r).matches(expected.pop());
                });
            }

            if (offsetCommit) {
                consumer.commitSync(Duration.ofSeconds(5));
            }

        }
    }

    private void produceAndVerify(String address, String topic, String key, String value) throws Exception {
        produceAndVerify(address, Stream.of(new ProducerRecord<>(topic, key, value)));
    }

    private void produceAndVerify(String address, Stream<ProducerRecord<String, String>> records) throws Exception {

        try (var producer = new KafkaProducer<String, String>(commonConfig(address, Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3_600_000)))) {

            records.forEach(rec -> {
                RecordMetadata recordMetadata;
                try {
                    recordMetadata = producer.send(rec).get();
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
                assertNotNull(recordMetadata);
                assertNotNull(rec.topic(), recordMetadata.topic());
            });

        }
    }

    private CreateTopicsResult createTopics(String address, List<NewTopic> topics) throws Exception {
        try (var admin = Admin.create(commonConfig(address, Map.of()))) {
            return createTopics(admin, topics);
        }
    }

    private static CreateTopicsResult createTopics(Admin admin, List<NewTopic> topics) throws Exception {
        var created = admin.createTopics(topics);
        assertEquals(topics.size(), created.values().size());
        created.all().get();
        return created;
    }

    private DeleteTopicsResult deleteTopics(String address, TopicCollection topics) throws Exception {
        try (var admin = Admin.create(commonConfig(
                address, Map.of()))) {
            return deleteTopics(admin, topics);
        }
    }

    private DeleteTopicsResult deleteTopics(Admin admin, TopicCollection topics) throws Exception {
        var deleted = admin.deleteTopics(topics);
        deleted.all().get();
        return deleted;
    }

    @NotNull
    private Map<String, Object> commonConfig(String address, Map<String, Object> m) {
        var config = new HashMap<String, Object>();
        config.putAll(m);
        config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, address);
        config.put(CommonClientConfigs.CLIENT_ID_CONFIG, testInfo.getDisplayName());
        config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name);
        config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, certificateGenerator.getCertLocation());
        config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, certificateGenerator.getPassword());
        return config;
    }

    private String getConfig(String proxyAddress, KafkaCluster cluster) {
        String config = """
                proxy:
                  address: %s
                  keyStoreFile: %s
                  keyPassword: %s
                clusters:
                  demo:
                    bootstrap_servers: %s
                addressManager:
                  type: FixedCluster
                  config:
                     bootstrap_servers: %s
                filters:
                - type: ApiVersions
                - type: BrokerAddress
                  config:
                    addressMapperClazz: io.kroxylicious.proxy.internal.filter.SniAddressMapping
                - type: MultiTenant
                """.formatted(proxyAddress, certificateGenerator.getCertLocation(), certificateGenerator.getPassword(), cluster.getBootstrapServers(),
                cluster.getBootstrapServers());
        return config;
    }

    private KafkaProxy startProxy(String config) throws InterruptedException {
        Configuration proxyConfig = new ConfigParser().parseConfiguration(config);

        KafkaProxy kafkaProxy = new KafkaProxy(proxyConfig);
        kafkaProxy.startup();

        return kafkaProxy;
    }

    @NotNull
    private <T, V> Condition<T> matches(Function<T, V> extractor, V expectedValue) {
        return new Condition<>(item -> Objects.equals(extractor.apply(item), expectedValue), "unexpected entry");
    }

    @NotNull
    private static <K, V> Predicate<ConsumerRecord<K, V>> matchesRecord(final String expectedTopic, final K expectedKey, final V expectedValue) {
        return item -> {
            var rec = ((ConsumerRecord<K, V>) item);
            return Objects.equals(rec.topic(), expectedTopic) && Objects.equals(rec.key(), expectedKey) && Objects.equals(rec.value(), expectedValue);
        };
    }
}
