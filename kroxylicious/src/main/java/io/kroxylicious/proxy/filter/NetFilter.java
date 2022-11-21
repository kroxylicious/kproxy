/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import java.net.SocketAddress;

/**
 * Abstracts some policy/logic for how an upstream connection for a given client connection
 * is made.
 */
public interface NetFilter {

    /**
     * Determine the upstream cluster to connect to based on the information
     * provided by the given {@code context},
     * by invoking {@link NetFilterContext#initiateConnect(String, int, KrpcFilter[])}.
     * @param context The context.
     */
    void selectServer(NetFilterContext context);

    interface NetFilterContext {
        /**
         * @return The source host of the client, taking into account source host information
         * propagated by intermediate proxies.
         * You can think of this as being like HTTP's {@code X-Forwarded-For} header.
         * @see #srcAddress()
         */
        public String clientHost();

        /**
         * @return The source port of the client, taking into account source host information
         * propagated by intermediate proxies.
         */
        public int clientPort();

        /**
         * @return The address of the remote TCP peer, which may the ultimate client,
         * but could be an intermediate proxy.
         * @see #clientHost()
         */
        public SocketAddress srcAddress();

        /**
         * The authorized id, or null if there is no authentication configured for this listener.
         * @return
         */
        public String authorizedId();

        /**
         * @return The name of the client software, if known via ApiVersions request. Otherwise null.
         */
        public String clientSoftwareName();

        /**
         * @return The version of the client software, if known via ApiVersions request. Otherwise null.
         */
        public String clientSoftwareVersion();

        /**
         * @return The <a href="https://en.wikipedia.org/wiki/Server_Name_Indication">SNI</a>
         * hostname which the client used during TLS handshake.
         */
        public String sniHostname();

        /**
         * Connect to the Kafka server at the given {@code host} and {@code port},
         * using the given protocol filters
         * @param host The host
         * @param port The port
         * @param filters The filters
         */
        public void initiateConnect(String host, int port, KrpcFilter[] filters);

        // TODO add API for delayed responses
    }
}
