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

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Spike P0.10 only: a pgjdbc {@code socketFactory} whose sockets tap the bytes the server sends, frame them as protocol
 * messages, and report each {@code NotificationResponse} ('A') payload with the {@link System#nanoTime()} of the socket
 * read that completed it. That is when the notification reached the client, independently of when pgjdbc's
 * {@code getNotifications} hands it out (under continuous traffic pgjdbc returns them only once the stream pauses).
 * Requires {@code sslmode=disable}: the framing starts at the first byte the server sends.
 * <p>
 * Public with a public no-arg constructor because pgjdbc instantiates it by class name
 * ({@code socketFactory=<this class>}).
 */
public class NotifyTapSocketFactory extends SocketFactory {
    /** Receives (payload, nanoTime) for every notification on a tapped connection. */
    static volatile Consumer<Tap> sink = t -> {
    };

    record Tap(String payload, long nanos) {
    }

    public NotifyTapSocketFactory() {
    }

    @Override
    public Socket createSocket() {
        return new TapSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        var s = new TapSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        var s = new TapSocket();
        s.bind(new InetSocketAddress(localHost, localPort));
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        var s = new TapSocket();
        s.connect(new InetSocketAddress(host, port));
        return s;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        var s = new TapSocket();
        s.bind(new InetSocketAddress(localAddress, localPort));
        s.connect(new InetSocketAddress(address, port));
        return s;
    }

    private static final class TapSocket extends Socket {
        private InputStream in;

        @Override
        public synchronized InputStream getInputStream() throws IOException {
            if (in == null) in = new TapInputStream(super.getInputStream());
            return in;
        }
    }

    /** Frames the server's byte stream: type byte, int32 length (including itself), body. */
    private static final class TapInputStream extends FilterInputStream {
        private final byte[] header = new byte[5];
        private int          headerFill;
        private int          bodyRemaining;
        private boolean      capture;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        TapInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) feed(new byte[]{(byte) b}, 0, 1, System.nanoTime());
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) feed(b, off, n, System.nanoTime());
            return n;
        }

        private void feed(byte[] b, int off, int n, long now) {
            int i = off, end = off + n;
            while (i < end) {
                if (headerFill < 5) {
                    header[headerFill++] = b[i++];
                    if (headerFill == 5) {
                        bodyRemaining = ((header[1] & 0xff) << 24 | (header[2] & 0xff) << 16 | (header[3] & 0xff) << 8 | (header[4] & 0xff)) - 4;
                        capture = header[0] == 'A';
                        body.reset();
                        if (bodyRemaining == 0) headerFill = 0;
                    }
                    continue;
                }
                int take = Math.min(bodyRemaining, end - i);
                if (capture) body.write(b, i, take);
                i += take;
                bodyRemaining -= take;
                if (bodyRemaining == 0) {
                    if (capture) emit(body.toByteArray(), now);
                    headerFill = 0;
                }
            }
        }

        /** NotificationResponse body: int32 pid, channel\0, payload\0. */
        private static void emit(byte[] m, long now) {
            int p = 4;
            while (p < m.length && m[p] != 0) p++;
            int start = p + 1, e = start;
            while (e < m.length && m[e] != 0) e++;
            if (start <= m.length) sink.accept(new Tap(new String(m, start, Math.max(0, e - start), StandardCharsets.UTF_8), now));
        }
    }
}
