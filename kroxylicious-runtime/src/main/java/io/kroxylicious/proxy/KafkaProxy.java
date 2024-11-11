/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringServerSocketChannel;
import io.netty.util.concurrent.Future;

import io.kroxylicious.proxy.bootstrap.FilterChainFactory;
import io.kroxylicious.proxy.config.Configuration;
import io.kroxylicious.proxy.config.MicrometerDefinition;
import io.kroxylicious.proxy.config.PluginFactoryRegistry;
import io.kroxylicious.proxy.config.admin.AdminHttpConfiguration;
import io.kroxylicious.proxy.internal.KafkaProxyInitializer;
import io.kroxylicious.proxy.internal.MeterRegistries;
import io.kroxylicious.proxy.internal.PortConflictDetector;
import io.kroxylicious.proxy.internal.admin.AdminHttpInitializer;
import io.kroxylicious.proxy.internal.net.DefaultNetworkBindingOperationProcessor;
import io.kroxylicious.proxy.internal.net.EndpointRegistry;
import io.kroxylicious.proxy.internal.net.NetworkBindingOperationProcessor;
import io.kroxylicious.proxy.internal.util.Metrics;
import io.kroxylicious.proxy.model.VirtualCluster;
import io.kroxylicious.proxy.service.HostPort;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public final class KafkaProxy implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaProxy.class);
    private static final Logger STARTUP_SHUTDOWN_LOGGER = LoggerFactory.getLogger("io.kroxylicious.proxy.StartupShutdownLogger");

    private record EventGroupConfig(String name, EventLoopGroup bossGroup, EventLoopGroup workerGroup, Class<? extends ServerChannel> clazz) {

        public List<Future<?>> shutdownGracefully() {
            return List.of(bossGroup.shutdownGracefully(), workerGroup.shutdownGracefully());
        }
    }

    private final @NonNull Configuration config;
    private final @Nullable AdminHttpConfiguration adminHttpConfig;
    private final @NonNull List<MicrometerDefinition> micrometerConfig;
    private final @NonNull List<VirtualCluster> virtualClusters;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CompletableFuture<Void> shutdown = new CompletableFuture<>();
    private final NetworkBindingOperationProcessor bindingOperationProcessor = new DefaultNetworkBindingOperationProcessor();
    private final EndpointRegistry endpointRegistry = new EndpointRegistry(bindingOperationProcessor);
    private final @NonNull PluginFactoryRegistry pfr;
    private @Nullable MeterRegistries meterRegistries;
    private @Nullable FilterChainFactory filterChainFactory;
    private @Nullable EventGroupConfig adminEventGroup;
    private @Nullable EventGroupConfig serverEventGroup;
    private @Nullable Channel metricsChannel;

    public KafkaProxy(PluginFactoryRegistry pfr, Configuration config) {
        this.pfr = Objects.requireNonNull(pfr);
        this.config = Objects.requireNonNull(config);
        this.virtualClusters = config.virtualClusterModel();
        this.adminHttpConfig = config.adminHttpConfig();
        this.micrometerConfig = config.getMicrometer();
    }

    /**
     * Starts this proxy.
     * @return This proxy.
     */
    public KafkaProxy startup() throws InterruptedException {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("This proxy is already running");
        }
        try {
            STARTUP_SHUTDOWN_LOGGER.info("Kroxylicious is starting");

            var portConflictDefector = new PortConflictDetector();
            Optional<HostPort> adminHttpHostPort = Optional.ofNullable(shouldBindAdminEndpoint() ? new HostPort(adminHttpConfig.host(), adminHttpConfig.port()) : null);
            portConflictDefector.validate(virtualClusters, adminHttpHostPort);

            meterRegistries = new MeterRegistries(micrometerConfig);

            this.adminEventGroup = buildNettyEventGroups("admin", 1, config.isUseIoUring());
            // Specifying 0 threads means we apply Netty defaults which are (2 * availableCores) or the system property io.netty.eventLoopThreads.
            this.serverEventGroup = buildNettyEventGroups("server", 0, config.isUseIoUring());

            maybeStartMetricsListener(adminEventGroup, meterRegistries);

            this.filterChainFactory = new FilterChainFactory(pfr, config.filters());
            var tlsServerBootstrap = buildServerBootstrap(serverEventGroup,
                    new KafkaProxyInitializer(filterChainFactory, pfr, true, endpointRegistry, endpointRegistry, false, Map.of()));
            var plainServerBootstrap = buildServerBootstrap(serverEventGroup,
                    new KafkaProxyInitializer(filterChainFactory, pfr, false, endpointRegistry, endpointRegistry, false, Map.of()));

            bindingOperationProcessor.start(plainServerBootstrap, tlsServerBootstrap);

            // TODO: startup/shutdown should return a completionstage
            CompletableFuture.allOf(
                    virtualClusters.stream().map(vc -> endpointRegistry.registerVirtualCluster(vc).toCompletableFuture()).toArray(CompletableFuture[]::new))
                    .join();

            // Pre-register counters/summaries to avoid creating them on first request and thus skewing the request latency
            // TODO add a virtual host tag to metrics
            Metrics.inboundDownstreamMessagesCounter();
            Metrics.inboundDownstreamDecodedMessagesCounter();
            return this;
        }
        catch (RuntimeException | InterruptedException e) {
            shutdown();
            throw e;
        }
    }

    private ServerBootstrap buildServerBootstrap(EventGroupConfig virtualHostEventGroup, KafkaProxyInitializer kafkaProxyInitializer) {
        return new ServerBootstrap()
                .group(virtualHostEventGroup.bossGroup(), virtualHostEventGroup.workerGroup())
                .channel(virtualHostEventGroup.clazz())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childHandler(kafkaProxyInitializer)
                .childOption(ChannelOption.TCP_NODELAY, true);
    }

    private EventGroupConfig buildNettyEventGroups(String name, int availableCores, boolean useIoUring) {
        final Class<? extends ServerChannel> channelClass;
        final EventLoopGroup bossGroup;
        final EventLoopGroup workerGroup;

        final IoHandlerFactory ioHandlerFactory;
        if (useIoUring && !IoUring.isAvailable()) {
            throw new IllegalStateException("io_uring not available due to: " + IoUring.unavailabilityCause());
        }
        if (IoUring.isAvailable() && useIoUring) {
            ioHandlerFactory = io.netty.channel.uring.IoUringIoHandler.newFactory();
            channelClass = IoUringServerSocketChannel.class;
        }
        else if (Epoll.isAvailable()) {
            ioHandlerFactory = EpollIoHandler.newFactory();
            channelClass = EpollServerSocketChannel.class;
        }
        else if (KQueue.isAvailable()) {
            ioHandlerFactory = KQueueIoHandler.newFactory();
            channelClass = KQueueServerSocketChannel.class;
        }
        else {
            ioHandlerFactory = NioIoHandler.newFactory();
            channelClass = NioServerSocketChannel.class;
        }

        bossGroup = new MultiThreadIoEventLoopGroup(availableCores, ioHandlerFactory);
        workerGroup = new MultiThreadIoEventLoopGroup(availableCores, ioHandlerFactory);

        return new EventGroupConfig(name, bossGroup, workerGroup, channelClass);
    }

    private void maybeStartMetricsListener(EventGroupConfig eventGroupConfig,
                                           MeterRegistries meterRegistries)
            throws InterruptedException {
        if (shouldBindAdminEndpoint()) {
            ServerBootstrap metricsBootstrap = new ServerBootstrap().group(eventGroupConfig.bossGroup(), eventGroupConfig.workerGroup())
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .channel(eventGroupConfig.clazz())
                    .childHandler(new AdminHttpInitializer(meterRegistries, adminHttpConfig));
            LOGGER.info("Binding metrics endpoint: {}:{}", adminHttpConfig.host(), adminHttpConfig.port());
            metricsChannel = metricsBootstrap.bind(adminHttpConfig.host(), adminHttpConfig.port()).sync().channel();
        }
    }

    private boolean shouldBindAdminEndpoint() {
        return adminHttpConfig != null
                && adminHttpConfig.endpoints().maybePrometheus().isPresent();
    }

    /**
     * Blocks while this proxy is running.
     * This should only be called after a successful call to {@link #startup()}.
     * @throws InterruptedException
     */
    public void block() throws Exception {
        if (!running.get()) {
            throw new IllegalStateException("This proxy is not running");
        }
        shutdown.join();
    }

    /**
     * Shuts down a running proxy.
     * @throws InterruptedException
     */
    public void shutdown() throws InterruptedException {
        if (!running.getAndSet(false)) {
            throw new IllegalStateException("This proxy is not running");
        }
        try {
            STARTUP_SHUTDOWN_LOGGER.info("Shutting down");
            endpointRegistry.shutdown().handle((u, t) -> {
                bindingOperationProcessor.close();
                var closeFutures = new ArrayList<Future<?>>();
                if (serverEventGroup != null) {
                    closeFutures.addAll(serverEventGroup.shutdownGracefully());
                }
                if (adminEventGroup != null) {
                    closeFutures.addAll(adminEventGroup.shutdownGracefully());
                }
                closeFutures.forEach(Future::syncUninterruptibly);
                if (filterChainFactory != null) {
                    filterChainFactory.close();
                }
                if (t != null) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    else {
                        throw new RuntimeException(t);
                    }
                }
                return null;
            }).toCompletableFuture().join();
            if (meterRegistries != null) {
                meterRegistries.close();
            }
        }
        finally {
            adminEventGroup = null;
            serverEventGroup = null;
            metricsChannel = null;
            meterRegistries = null;
            filterChainFactory = null;
            shutdown.complete(null);
            LOGGER.info("Shut down completed.");

        }
    }

    @Override
    public void close() throws Exception {
        if (running.get()) {
            shutdown();
        }
    }

}
