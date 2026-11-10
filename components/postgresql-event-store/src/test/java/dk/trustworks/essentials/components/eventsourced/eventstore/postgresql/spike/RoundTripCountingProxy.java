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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Throwaway TCP proxy for the phase-0 baseline (P0.0): forwards bytes between a JDBC client and PostgreSQL and counts
 * <em>client flights</em> - a burst of bytes the client sends after it last received something. Since the client can
 * only send a dependent request once it has read the reply to the previous one, the number of flights is the number of
 * network round trips the client needed. Used for counting, never for latency (it adds a hop).
 */
final class RoundTripCountingProxy implements AutoCloseable {
    private final ServerSocket    server;
    private final String          targetHost;
    private final int             targetPort;
    private final AtomicLong      clientFlights = new AtomicLong();
    private final ExecutorService threads       = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "round-trip-proxy");
        t.setDaemon(true);
        return t;
    });

    RoundTripCountingProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 100, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    long flights() {
        return clientFlights.get();
    }

    void reset() {
        clientFlights.set(0);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                var client   = server.accept();
                var upstream = new Socket(targetHost, targetPort);
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                // true = the last bytes seen on this connection came from the client
                var lastFromClient = new AtomicBoolean(false);
                threads.submit(() -> pump(client, upstream, true, lastFromClient));
                threads.submit(() -> pump(upstream, client, false, lastFromClient));
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private void pump(Socket from, Socket to, boolean fromClient, AtomicBoolean lastFromClient) {
        var buffer = new byte[64 * 1024];
        try (var in = from.getInputStream(); var out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (fromClient) {
                    if (!lastFromClient.getAndSet(true)) {
                        clientFlights.incrementAndGet();
                    }
                } else {
                    lastFromClient.set(false);
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // connection closed
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        threads.shutdownNow();
    }
}
