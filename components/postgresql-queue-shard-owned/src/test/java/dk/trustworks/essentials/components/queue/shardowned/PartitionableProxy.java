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

package dk.trustworks.essentials.components.queue.shardowned;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * A TCP forwarder that can black-hole traffic <b>without closing the connection</b>, so a test can
 * partition one party from the database while both keep running.
 *
 * <h2>Why not just kill the connections</h2>
 * {@code pg_terminate_backend} — what {@code ShardOwnedConnectionLossIT} uses — is a connection
 * <em>reset</em>: the client gets an RST and learns instantly, reconnects, and keeps its leases. A
 * partition is the opposite and is the harder case: packets vanish, no FIN, no RST, and the client
 * blocks until its socket timeout. Only then does the heartbeat stop renewing, the instance go stale,
 * and another node take the units. The two failures exercise completely different paths, and the
 * second is the one an operator actually meets — a pod losing its network is ordinary.
 *
 * <h2>Why this rather than Toxiproxy</h2>
 * Toxiproxy is the standard tool and would work. It costs two dependencies and a pinned image that
 * CI must pre-pull, for one behaviour that is a few lines of socket code. Doing it here also makes
 * the semantics explicit rather than a toxic name: {@link #partition()} stops the pumps from
 * <em>reading</em>, so TCP backpressure stalls both directions and nothing is silently swallowed —
 * the bytes are still in the sender's socket buffer when {@link #heal()} lets them through.
 */
final class PartitionableProxy implements AutoCloseable {

    private final ServerSocket        listener;
    private final String              upstreamHost;
    private final int                 upstreamPort;
    private final ExecutorService     threads = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "partitionable-proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Socket>        open    = new CopyOnWriteArrayList<>();
    private volatile boolean          partitioned;
    private volatile boolean          closed;

    PartitionableProxy(String upstreamHost, int upstreamPort) throws IOException {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.listener     = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        threads.submit(this::acceptLoop);
    }

    int localPort() {
        return listener.getLocalPort();
    }

    /**
     * Stop passing bytes in either direction, leaving every socket open. A caller blocks until its
     * own socket timeout; it is never told anything is wrong, which is the point.
     */
    void partition() {
        partitioned = true;
    }

    /** Let traffic through again. Whatever the sender still holds is delivered. */
    void heal() {
        partitioned = false;
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                var downstream = listener.accept();
                // A connection opened DURING a partition must also hang rather than be refused:
                // a refusal is an answer, and a partition does not give answers.
                threads.submit(() -> {
                    while (partitioned && !closed) {
                        sleep();
                    }
                    connect(downstream);
                });
            } catch (IOException e) {
                if (!closed) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    private void connect(Socket downstream) {
        try {
            var upstream = new Socket(upstreamHost, upstreamPort);
            open.add(downstream);
            open.add(upstream);
            threads.submit(() -> pump(downstream, upstream));
            threads.submit(() -> pump(upstream, downstream));
        } catch (IOException e) {
            closeQuietly(downstream);
        }
    }

    /**
     * While partitioned this does not read at all, rather than reading and discarding. Discarding
     * would tell the sender its bytes were delivered; not reading lets TCP's own backpressure stall
     * it, which is what a black hole actually looks like from the sender's side.
     */
    private void pump(Socket from, Socket to) {
        var buffer = new byte[8192];
        try (from; to) {
            var in  = from.getInputStream();
            var out = to.getOutputStream();
            while (!closed) {
                while (partitioned && !closed) {
                    sleep();
                }
                if (in.available() == 0) {
                    sleep();
                    continue;
                }
                var read = in.read(buffer);
                if (read < 0) {
                    return;
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // A partitioned peer timing out and closing is an expected end to a pump.
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not a failure.
        }
    }

    @Override
    public void close() {
        closed = true;
        open.forEach(PartitionableProxy::closeQuietly);
        closeQuietly(listener);
        threads.shutdownNow();
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // As above.
        }
    }
}
