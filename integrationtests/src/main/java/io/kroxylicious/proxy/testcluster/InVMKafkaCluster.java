/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.testcluster;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.kafka.common.utils.Time;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.testcluster.ClusterConfig.ConfigHolder;
import io.kroxylicious.proxy.testcluster.ClusterConfig.KafkaEndpoints;
import kafka.server.KafkaConfig;
import kafka.server.KafkaRaftServer;
import kafka.server.KafkaServer;
import kafka.server.Server;
import kafka.tools.StorageTool;
import scala.Option;

public class InVMKafkaCluster implements Cluster {
    private static final Logger LOGGER = LoggerFactory.getLogger(InVMKafkaCluster.class);

    private final ClusterConfig clusterConfig;
    private final Path tempDirectory;
    private final ServerCnxnFactory zooFactory;
    private final ZooKeeperServer zooServer;
    private List<Server> servers;
    private List<String> bootstraps = new ArrayList<>();

    public InVMKafkaCluster(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
        try {
            tempDirectory = Files.createTempDirectory("kafka");

            var numPorts = clusterConfig.getBrokersNum() * (clusterConfig.isKraftMode() ? 3 : 2) + (clusterConfig.isKraftMode() ? 0 : 1);
            LinkedList<Integer> ports = Utils.preAllocateListeningPorts(numPorts).collect(Collectors.toCollection(LinkedList::new));

            final Supplier<KafkaEndpoints.Endpoint> zookeeperEndpointSupplier;
            if (!clusterConfig.isKraftMode()) {
                var zookeeperPort = ports.pop();

                zooFactory = ServerCnxnFactory.createFactory(new InetSocketAddress("localhost", zookeeperPort), 1024);

                var zoo = tempDirectory.resolve("zoo");
                var snapshotDir = zoo.resolve("snapshot");
                var logDir = zoo.resolve("log");
                snapshotDir.toFile().mkdirs();
                logDir.toFile().mkdirs();

                zooServer = new ZooKeeperServer(snapshotDir.toFile(), logDir.toFile(), 500);
                zookeeperEndpointSupplier = () -> new KafkaEndpoints.Endpoint("localhost", zookeeperPort);
            }
            else {
                zooFactory = null;
                zooServer = null;
                zookeeperEndpointSupplier = null;
            }

            Supplier<KafkaEndpoints> kafkaEndpointsSupplier = () -> new KafkaEndpoints() {
                final List<Integer> clientPorts = ports.subList(0, clusterConfig.getBrokersNum());
                final List<Integer> interBrokerPorts = ports.subList(clusterConfig.getBrokersNum(), 2 * clusterConfig.getBrokersNum());
                final List<Integer> controllerPorts = ports.subList(clusterConfig.getBrokersNum() * 2, ports.size());

                @Override
                public EndpointPair getClientEndpoint(int brokerNum) {
                    var port = clientPorts.get(brokerNum);
                    return EndpointPair.builder().bind(new Endpoint("0.0.0.0", port)).connect(new Endpoint("localhost", port)).build();
                }

                @Override
                public EndpointPair getInterBrokerEndpoint(int brokerNum) {
                    var port = interBrokerPorts.get(brokerNum);
                    return EndpointPair.builder().bind(new Endpoint("0.0.0.0", port)).connect(new Endpoint("localhost", port)).build();
                }

                @Override
                public EndpointPair getControllerEndpoint(int brokerNum) {
                    if (!clusterConfig.isKraftMode()) {
                        throw new IllegalStateException();
                    }
                    var port = controllerPorts.get(brokerNum);
                    return EndpointPair.builder().bind(new Endpoint("0.0.0.0", port)).connect(new Endpoint("localhost", port)).build();
                }
            };

            servers = clusterConfig.getBrokerConfigs(kafkaEndpointsSupplier, zookeeperEndpointSupplier).map(this::buildKafkaServer).collect(Collectors.toList());

        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @NotNull
    private Server buildKafkaServer(ConfigHolder c) {
        bootstraps.add(c.getEndpoint());
        KafkaConfig config = buildBrokerConfig(c, tempDirectory);
        Option<String> threadNamePrefix = Option.apply(null);

        boolean kraftMode = clusterConfig.isKraftMode();
        if (kraftMode) {
            var directories = StorageTool.configToLogDirectories(config);
            var clusterId = c.getKafkaKraftClusterId();
            var metaProperties = StorageTool.buildMetadataProperties(clusterId, config);
            StorageTool.formatCommand(System.out, directories, metaProperties, true);
            return new KafkaRaftServer(config, Time.SYSTEM, threadNamePrefix);
        }
        else {
            return new KafkaServer(config, Time.SYSTEM, threadNamePrefix, false);

        }
    }

    @NotNull
    private KafkaConfig buildBrokerConfig(ConfigHolder c, Path tempDirectory) {
        Properties properties = new Properties();
        properties.putAll(c.getProperties());
        Path logsDir = tempDirectory.resolve(String.format("broker-%d", c.getBrokerNum()));
        properties.setProperty(KafkaConfig.LogDirProp(), logsDir.toAbsolutePath().toString());

        return new KafkaConfig(properties);
    }

    @Override
    public void start() {
        if (zooFactory != null) {
            try {
                zooFactory.startup(zooServer);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                zooServer.shutdown(true);
                return;
            }
        }

        servers.stream().parallel().forEach(Server::startup);

    }

    @Override
    public String getBootstrapServers() {
        return String.join(",", bootstraps);
    }

    @Override
    public Map<String, Object> getConnectConfigForCluster() {
        return clusterConfig.getConnectConfigForCluster(getBootstrapServers());
    }

    @Override
    public void close() throws Exception {
        try {
            servers.stream().parallel().forEach(Server::shutdown);
            bootstraps.clear();

            if (zooServer != null) {
                zooServer.shutdown(true);
            }
        }
        finally {
            if (tempDirectory.toFile().exists()) {
                try (var s = Files.walk(tempDirectory)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)) {
                    s.forEach(File::delete);
                }
            }
        }
    }
}
