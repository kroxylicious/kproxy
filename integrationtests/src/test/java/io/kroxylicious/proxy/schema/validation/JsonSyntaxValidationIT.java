/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.schema.validation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.KafkaException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.kroxylicious.proxy.config.FilterDefinitionBuilder;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;

import static io.kroxylicious.test.tester.KroxyliciousConfigUtils.proxy;
import static io.kroxylicious.test.tester.KroxyliciousConfigUtils.withDefaultFilters;
import static io.kroxylicious.test.tester.KroxyliciousTesters.kroxyliciousTester;
import static java.util.UUID.randomUUID;
import static org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.LINGER_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.TRANSACTIONAL_ID_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(KafkaClusterExtension.class)
public class JsonSyntaxValidationIT {

    public static final String SYNTACTICALLY_CORRECT_JSON = "{\"value\":\"json\"}";
    public static final String SYNTACTICALLY_INCORRECT_JSON = "Not Json";
    private static final String TOPIC_1 = "my-test-topic";
    private static final String TOPIC_2 = "my-test-topic-2";

    @Test
    public void testInvalidJsonProduceRejected(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());
        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());
        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(0, 16384))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
        }
    }

    @Test
    public void testInvalidJsonProduceRejectedUsingTopicNames(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());
        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1), new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());
        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(0, 16384));
                var consumer = tester.consumer(Map.of(GROUP_ID_CONFIG, "my-group-id", AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            producer.send(new ProducerRecord<>(TOPIC_2, "my-key", SYNTACTICALLY_INCORRECT_JSON)).get();
            consumer.subscribe(Set.of(TOPIC_2));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            assertEquals(SYNTACTICALLY_INCORRECT_JSON, records.iterator().next().value());
        }
    }

    @Test
    public void testPartiallyInvalidJsonTransactionalAllRejected(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1), new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("forwardPartialRequests", true, "rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1, TOPIC_2), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(Map.of(LINGER_MS_CONFIG, 5000, TRANSACTIONAL_ID_CONFIG, randomUUID().toString()))) {
            producer.initTransactions();
            producer.beginTransaction();
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            Future<RecordMetadata> valid = producer.send(new ProducerRecord<>(TOPIC_2, "my-key", SYNTACTICALLY_CORRECT_JSON));
            producer.flush();
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            assertInvalidRecordExceptionThrown(valid, "Invalid record in another topic-partition caused whole ProduceRequest to be invalidated");
            producer.abortTransaction();
        }
    }

    @Test
    public void testPartiallyInvalidJsonNotConfiguredToForwardAllRejected(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1), new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        boolean forwardPartialRequests = false;
        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("forwardPartialRequests", forwardPartialRequests, "rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1, TOPIC_2), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(5000, 16384))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            Future<RecordMetadata> valid = producer.send(new ProducerRecord<>(TOPIC_2, "my-key", SYNTACTICALLY_CORRECT_JSON));
            producer.flush();
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            assertInvalidRecordExceptionThrown(valid, "Invalid record in another topic-partition caused whole ProduceRequest to be invalidated");
        }
    }

    @Test
    public void testPartiallyInvalidJsonProduceRejected(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1), new NewTopic(TOPIC_2, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator")
                        .withConfig("forwardPartialRequests", true,
                                "rules", List.of(Map.of("topicNames", List.of(TOPIC_1, TOPIC_2), "valueRule",
                                        Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(5000, 16384));
                var consumer = tester.consumer(Map.of(GROUP_ID_CONFIG, "my-group-id", AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            Future<RecordMetadata> valid = producer.send(new ProducerRecord<>(TOPIC_2, "my-key", SYNTACTICALLY_CORRECT_JSON));
            producer.flush();
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            RecordMetadata metadata = valid.get(10, TimeUnit.SECONDS);
            assertTrue(metadata.hasOffset());

            consumer.subscribe(Set.of(TOPIC_2));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            assertEquals(SYNTACTICALLY_CORRECT_JSON, records.iterator().next().value());
        }
    }

    @Test
    public void testPartiallyInvalidAcrossPartitionsOfSameTopic(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 2, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator")
                        .withConfig("forwardPartialRequests", true,
                                "rules", List.of(Map.of("topicNames", List.of(TOPIC_1), "valueRule",
                                        Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(5000, 16384));
                var consumer = tester.consumer(Map.of(GROUP_ID_CONFIG, "my-group-id", AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, 0, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            Future<RecordMetadata> valid = producer.send(new ProducerRecord<>(TOPIC_1, 1, "my-key", SYNTACTICALLY_CORRECT_JSON));
            producer.flush();
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            RecordMetadata metadata = valid.get(10, TimeUnit.SECONDS);
            assertTrue(metadata.hasOffset());

            consumer.subscribe(Set.of(TOPIC_1));
            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            assertEquals(SYNTACTICALLY_CORRECT_JSON, records.iterator().next().value());
        }
    }

    @Test
    public void testPartiallyInvalidWithinOnePartitionOfTopic(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("forwardPartialRequests", true, "rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(5000, 16384))) {
            Future<RecordMetadata> invalid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_INCORRECT_JSON));
            Future<RecordMetadata> valid = producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_CORRECT_JSON));
            producer.flush();
            assertInvalidRecordExceptionThrown(invalid, "value was not syntactically correct JSON");
            Assertions.assertThatThrownBy(() -> {
                valid.get(10, TimeUnit.SECONDS);
            }).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(KafkaException.class).cause()
                    .hasMessageContaining("Failed to append record because it was part of a batch which had one more more invalid records");
        }
    }

    @Test
    public void testValidJsonProduceAccepted(KafkaCluster cluster, Admin admin) throws Exception {
        assertEquals(1, cluster.getNumOfBrokers());

        admin.createTopics(List.of(new NewTopic(TOPIC_1, 1, (short) 1))).all().get();

        var config = withDefaultFilters(proxy(cluster))
                .addToFilters(new FilterDefinitionBuilder("ProduceValidator").withConfig("rules",
                        List.of(Map.of("topicNames", List.of(TOPIC_1), "valueRule",
                                Map.of("allowsNulls", true, "syntacticallyCorrectJson", Map.of("validateObjectKeysUnique", true)))))
                        .build());

        try (var tester = kroxyliciousTester(config);
                var producer = tester.producer(getProducerConfig(0, 16384))) {
            producer.send(new ProducerRecord<>(TOPIC_1, "my-key", SYNTACTICALLY_CORRECT_JSON)).get();
        }
    }

    private static Map<String, Object> getProducerConfig(int linger, int batchSize) {
        return Map.of(LINGER_MS_CONFIG, linger, ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
    }

    private static void assertInvalidRecordExceptionThrown(Future<RecordMetadata> invalid, String message) {
        Assertions.assertThatThrownBy(() -> {
            invalid.get(10, TimeUnit.SECONDS);
        }).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(InvalidRecordException.class).cause()
                .hasMessageContaining(message);
    }

}
