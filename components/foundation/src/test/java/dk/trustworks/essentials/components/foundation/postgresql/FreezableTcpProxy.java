/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.foundation.postgresql;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * A TCP proxy for tests that can make the connections passing through it <b>half-open</b>: after
 * {@link #freezeOpenConnections()} it stops forwarding bytes on every connection that is open at that moment, in both
 * directions, without closing either socket. Neither the client nor the server sees an error or an end of stream -
 * exactly what a dropped NAT entry, a failed-over load balancer or a silent network partition looks like. Connections
 * opened after the freeze are forwarded normally, so a client that reconnects recovers.
 * <p>
 * {@code pg_terminate_backend} cannot simulate this: PostgreSQL sends an error and closes the socket, which the client
 * notices on its next read.
 */
final class FreezableTcpProxy implements AutoCloseable {
    private final ServerSocket          serverSocket;
    private final String                targetHost;
    private final int                   targetPort;
    private final List<ProxyConnection> connections = new CopyOnWriteArrayList<>();
    private final ExecutorService       executor    = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "FreezableTcpProxy");
        thread.setDaemon(true);
        return thread;
    });

    FreezableTcpProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        executor.submit(this::acceptConnections);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String jdbcUrl(String databaseName) {
        return "jdbc:postgresql://127.0.0.1:" + port() + "/" + databaseName;
    }

    /**
     * Stop forwarding on every connection open now, without closing anything
     */
    void freezeOpenConnections() {
        connections.forEach(connection -> connection.frozen = true);
    }

    private void acceptConnections() {
        while (!serverSocket.isClosed()) {
            try {
                var client     = serverSocket.accept();
                var upstream   = new Socket(targetHost, targetPort);
                var connection = new ProxyConnection(client, upstream);
                connections.add(connection);
                executor.submit(() -> pump(connection, client.getInputStream(), upstream.getOutputStream()));
                executor.submit(() -> pump(connection, upstream.getInputStream(), client.getOutputStream()));
            } catch (IOException e) {
                // The server socket was closed
            }
        }
    }

    private static Void pump(ProxyConnection connection, InputStream in, OutputStream out) {
        var buffer = new byte[8192];
        try {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (connection.frozen) {
                    // Swallow - the peer never hears about it
                    continue;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Closed
        }
        if (!connection.frozen) {
            connection.close();
        }
        return null;
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            // Ignore
        }
        connections.forEach(ProxyConnection::close);
        executor.shutdownNow();
    }

    private static final class ProxyConnection {
        private final    Socket  client;
        private final    Socket  upstream;
        private volatile boolean frozen;

        private ProxyConnection(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        private void close() {
            try {
                client.close();
            } catch (IOException e) {
                // Ignore
            }
            try {
                upstream.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
