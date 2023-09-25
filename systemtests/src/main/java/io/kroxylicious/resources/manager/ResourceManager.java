/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.resources.manager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.Spec;
import io.strimzi.api.kafka.model.status.Status;

import io.kroxylicious.Constants;
import io.kroxylicious.enums.ConditionStatus;
import io.kroxylicious.resources.Resource;
import io.kroxylicious.resources.ResourceCondition;
import io.kroxylicious.resources.ResourceOperation;
import io.kroxylicious.resources.ResourceType;
import io.kroxylicious.resources.strimzi.KafkaNodePoolResource;
import io.kroxylicious.resources.strimzi.KafkaResource;
import io.kroxylicious.resources.strimzi.KafkaTopicResource;
import io.kroxylicious.resources.strimzi.KafkaUserResource;
import io.kroxylicious.utils.TestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type Resource manager.
 */
public class ResourceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceManager.class);
    /**
     * The constant STORED_RESOURCES.
     */
    public static final Map<String, Stack<Resource>> STORED_RESOURCES = new LinkedHashMap<>();
    private static ResourceManager instance;

    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static synchronized ResourceManager getInstance() {
        if (instance == null) {
            instance = new ResourceManager();
        }
        return instance;
    }

    private final ResourceType<?>[] resourceTypes = new ResourceType[]{
            new KafkaResource(),
            new KafkaTopicResource(),
            new KafkaUserResource(),
            new KafkaNodePoolResource()
    };

    /**
     * Create resource without wait.
     *
     * @param <T>  the type parameter
     * @param testInfo the test info
     * @param resources the resources
     */
    @SafeVarargs
    public final <T extends HasMetadata> void createResourceWithoutWait(TestInfo testInfo, T... resources) {
        createResource(testInfo, false, resources);
    }

    /**
     * Create resource with wait.
     *
     * @param <T>  the type parameter
     * @param testInfo the test info
     * @param resources the resources
     */
    @SafeVarargs
    public final <T extends HasMetadata> void createResourceWithWait(TestInfo testInfo, T... resources) {
        createResource(testInfo, true, resources);
    }

    @SafeVarargs
    private final <T extends HasMetadata> void createResource(TestInfo testInfo, boolean waitReady, T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);

            LOGGER.info("Creating/Updating {} {}",
                    resource.getKind(), resource.getMetadata().getName());

            assert type != null;
            type.create(resource);

            synchronized (this) {
                STORED_RESOURCES.computeIfAbsent(testInfo.getDisplayName(), k -> new Stack<>());
                STORED_RESOURCES.get(testInfo.getDisplayName()).push(
                        new Resource<T>(
                                () -> deleteResource(resource),
                                resource));
            }
        }

        if (waitReady) {
            for (T resource : resources) {
                ResourceType<T> type = findResourceType(resource);
                if (Objects.equals(resource.getKind(), KafkaTopic.RESOURCE_KIND)) {
                    continue;
                }
                if (!waitResourceCondition(resource, ResourceCondition.readiness(type))) {
                    throw new RuntimeException(String.format("Timed out waiting for %s %s/%s to be ready", resource.getKind(), resource.getMetadata().getNamespace(),
                            resource.getMetadata().getName()));
                }
            }
        }
    }

    /**
     * Delete resource.
     *
     * @param <T>  the type parameter
     * @param resources the resources
     */
    @SafeVarargs
    public final <T extends HasMetadata> void deleteResource(T... resources) {
        for (T resource : resources) {
            ResourceType<T> type = findResourceType(resource);

            if (type == null) {
                LOGGER.warn("Can't find resource type, please delete it manually");
                continue;
            }

            LOGGER.info("Deleting of {} {}/{}",
                    resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName());

            try {
                type.delete(resource);
                assertTrue(waitResourceCondition(resource, ResourceCondition.deletion()),
                        String.format("Timed out deleting %s %s/%s", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
            }
            catch (Exception e) {
                LOGGER.error("Failed to delete {} {}/{}", resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName(), e);
            }
        }
    }

    /**
     * Delete resources.
     *
     * @param testInfo the test info
     */
    public void deleteResources(TestInfo testInfo) {
        LOGGER.info(String.join("", Collections.nCopies(76, "#")));
        if (!STORED_RESOURCES.containsKey(testInfo.getDisplayName()) || STORED_RESOURCES.get(testInfo.getDisplayName()).isEmpty()) {
            LOGGER.info("For test {} is everything deleted", testInfo.getDisplayName());
        }
        else {
            LOGGER.info("Deleting all resources for {}", testInfo.getDisplayName());
        }

        // if stack is created for specific test suite or test case
        AtomicInteger numberOfResources = STORED_RESOURCES.get(testInfo.getDisplayName()) != null
                ? new AtomicInteger(STORED_RESOURCES.get(testInfo.getDisplayName()).size())
                :
                // stack has no elements
                new AtomicInteger(0);
        while (STORED_RESOURCES.containsKey(testInfo.getDisplayName()) && numberOfResources.get() > 0) {
            Stack<Resource> s = STORED_RESOURCES.get(testInfo.getDisplayName());

            while (!s.isEmpty()) {
                Resource resource = s.pop();

                try {
                    resource.getThrowableRunner().run();
                }
                catch (Exception e) {
                    LOGGER.trace(e.getMessage());
                }
                numberOfResources.decrementAndGet();
            }
        }
        STORED_RESOURCES.remove(testInfo.getDisplayName());
        LOGGER.info(String.join("", Collections.nCopies(76, "#")));
    }

    /**
     * Wait resource condition boolean.
     *
     * @param <T>  the type parameter
     * @param resource the resource
     * @param condition the condition
     * @return the boolean
     */
    public final <T extends HasMetadata> boolean waitResourceCondition(T resource, ResourceCondition<T> condition) {
        assertNotNull(resource);
        assertNotNull(resource.getMetadata());
        assertNotNull(resource.getMetadata().getName());

        ResourceType<T> type = findResourceType(resource);
        assertNotNull(type);
        boolean[] resourceReady = new boolean[1];

        TestUtils.waitFor(
                "resource condition: " + condition.getConditionName() + " to be fulfilled for resource " + resource.getKind() + ":" + resource.getMetadata().getName(),
                Constants.GLOBAL_POLL_INTERVAL_MILLIS, ResourceOperation.getTimeoutForResourceReadiness(resource.getKind()),
                () -> {
                    T res = type.get(resource.getMetadata().getNamespace(), resource.getMetadata().getName());
                    resourceReady[0] = condition.getPredicate().test(res);
                    if (!resourceReady[0]) {
                        type.delete(res);
                    }
                    return resourceReady[0];
                });

        return resourceReady[0];
    }

    @SuppressWarnings(value = "unchecked")
    private <T extends HasMetadata> ResourceType<T> findResourceType(T resource) {
        for (ResourceType<?> type : resourceTypes) {
            if (type.getKind().equals(resource.getKind())) {
                return (ResourceType<T>) type;
            }
        }
        return null;
    }

    /**
     * Wait until the CR is in desired state
     * @param <T>  the type parameter
     * @param operation - client of CR - for example kafkaClient()
     * @param resource - custom resource
     * @param statusType - desired status
     * @param resourceTimeout the resource timeout
     * @return returns CR
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> boolean waitForResourceStatus(MixedOperation<T, ?, ?> operation, T resource,
                                                                                                             Enum<?> statusType, long resourceTimeout) {
        return waitForResourceStatus(operation, resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName(), statusType,
                ConditionStatus.True, resourceTimeout);
    }

    /**
     * Wait for resource status boolean.
     *
     * @param <T>  the type parameter
     * @param operation the operation
     * @param kind the kind
     * @param namespace the namespace
     * @param name the name
     * @param statusType the status type
     * @param resourceTimeoutMs the resource timeout ms
     * @return the boolean
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> boolean waitForResourceStatus(MixedOperation<T, ?, ?> operation, String kind,
                                                                                                             String namespace, String name, Enum<?> statusType,
                                                                                                             long resourceTimeoutMs) {
        return waitForResourceStatus(operation, kind, namespace, name, statusType, ConditionStatus.True, resourceTimeoutMs);
    }

    /**
     * Wait for resource status boolean.
     *
     * @param <T>  the type parameter
     * @param operation the operation
     * @param kind the kind
     * @param namespace the namespace
     * @param name the name
     * @param statusType the status type
     * @param conditionStatus the condition status
     * @param resourceTimeoutMs the resource timeout ms
     * @return the boolean
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> boolean waitForResourceStatus(MixedOperation<T, ?, ?> operation, String kind,
                                                                                                             String namespace, String name, Enum<?> statusType,
                                                                                                             ConditionStatus conditionStatus, long resourceTimeoutMs) {
        LOGGER.info("Waiting for {}: {}/{} will have desired state: {}", kind, namespace, name, statusType);

        TestUtils.waitFor(String.format("%s: %s#%s will have desired state: %s", kind, namespace, name, statusType),
                Constants.POLL_INTERVAL_FOR_RESOURCE_READINESS_MILLIS, resourceTimeoutMs,
                () -> operation.inNamespace(namespace)
                        .withName(name)
                        .get().getStatus().getConditions().stream()
                        .anyMatch(condition -> condition.getType().equals(statusType.toString()) && condition.getStatus().equals(conditionStatus.toString())));

        LOGGER.info("{}: {}/{} is in desired state: {}", kind, namespace, name, statusType);
        return true;
    }

    /**
     * Wait for resource status boolean.
     *
     * @param <T>  the type parameter
     * @param operation the operation
     * @param resource the resource
     * @param status the status
     * @return the boolean
     */
    public static <T extends CustomResource<? extends Spec, ? extends Status>> boolean waitForResourceStatus(MixedOperation<T, ?, ?> operation, T resource,
                                                                                                             Enum<?> status) {
        long resourceTimeout = ResourceOperation.getTimeoutForResourceReadiness(resource.getKind());
        return waitForResourceStatus(operation, resource, status, resourceTimeout);
    }
}
