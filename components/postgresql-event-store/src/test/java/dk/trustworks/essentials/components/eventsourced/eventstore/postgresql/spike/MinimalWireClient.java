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

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * Throwaway minimal PostgreSQL wire-protocol (v3) client for spike P0.16's arm C: the settings go in the startup
 * message, every statement is parsed in one round trip at connect (Parse per statement, one Sync, no Describe), and each
 * call is Bind (binary parameters, binary results) + Execute + Sync. Several calls can be queued and sent in one flush
 * ({@link #queue}, {@link #flush}, then {@link #receive} once per queued call).
 * <p>
 * Plain TCP only (no TLS), no cancel request, no COPY, no Describe; authentication: trust, cleartext, MD5 and
 * SCRAM-SHA-256 (the server signature is not verified). Not engine code; spikes only.
 */
final class MinimalWireClient implements AutoCloseable {
    /** The parameter types the spikes bind, with their binary encodings. */
    enum Type {
        INT8(20), INT4(23), TEXT(25), INT8_ARRAY(1016);

        final int oid;

        Type(int oid) {
            this.oid = oid;
        }
    }

    /** The outcome of one Sync: data rows read (each field copied out, as a driver materializes it) or the command tag's count. */
    record Result(int rows, long affected, long firstColumnOfLastRow) {
    }

    private final Socket           socket;
    private final DataInputStream  in;
    private final DataOutputStream out;
    private final Body             body = new Body();
    private byte[]                 message = new byte[8192];
    private int                    messageLength;

    MinimalWireClient(String host, int port, String database, String user, String password, String options) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), 10_000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
        try {
            startup(database, user, password, options);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Startup and authentication
    // ------------------------------------------------------------------------------------------------------------------

    private void startup(String database, String user, String password, String options) throws IOException {
        body.reset();
        body.int4(196608);
        body.cstring("user").cstring(user).cstring("database").cstring(database).cstring("client_encoding").cstring("UTF8")
            .cstring("application_name").cstring("p016-wire");
        if (options != null && !options.isBlank()) body.cstring("options").cstring(options);
        body.write(0);
        out.writeInt(body.size() + 4);
        body.writeTo(out);
        out.flush();

        String scramClientFirstBare = null, scramNonce = null;
        while (true) {
            char type = read();
            switch (type) {
                case 'R' -> {
                    int code = int4(0);
                    switch (code) {
                        case 0 -> {
                        }
                        case 3 -> send('p', new Body().cstring(password));
                        case 5 -> {
                            var salt  = Arrays.copyOfRange(message, 4, 8);
                            var inner = hex(md5(concat((password + user).getBytes(StandardCharsets.UTF_8))));
                            var outer = "md5" + hex(md5(concat(inner.getBytes(StandardCharsets.UTF_8), salt)));
                            send('p', new Body().cstring(outer));
                        }
                        case 10 -> {
                            var mechanisms = new String(message, 4, messageLength - 4, StandardCharsets.UTF_8);
                            if (!mechanisms.contains("SCRAM-SHA-256")) throw new IOException("unsupported SASL mechanisms: " + mechanisms);
                            var random = new byte[18];
                            new SecureRandom().nextBytes(random);
                            scramNonce = Base64.getEncoder().encodeToString(random);
                            scramClientFirstBare = "n=,r=" + scramNonce;
                            var first = ("n,," + scramClientFirstBare).getBytes(StandardCharsets.UTF_8);
                            var b     = new Body().cstring("SCRAM-SHA-256");
                            b.int4(first.length);
                            b.write(first);
                            send('p', b);
                        }
                        case 11 -> {
                            var serverFirst = new String(message, 4, messageLength - 4, StandardCharsets.UTF_8);
                            send('p', new Body().bytes(scramFinal(password, scramClientFirstBare, scramNonce, serverFirst).getBytes(StandardCharsets.UTF_8)));
                        }
                        case 12 -> {
                            // SASL final: the server signature, not verified here
                        }
                        default -> throw new IOException("unsupported authentication request " + code);
                    }
                    out.flush();
                }
                case 'E' -> throw new IOException("startup failed: " + error());
                case 'Z' -> {
                    return;
                }
                default -> {
                    // ParameterStatus, BackendKeyData, NoticeResponse
                }
            }
        }
    }

    private static String scramFinal(String password, String clientFirstBare, String clientNonce, String serverFirst) throws IOException {
        var attributes = new HashMap<Character, String>();
        for (var part : serverFirst.split(",")) attributes.put(part.charAt(0), part.substring(2));
        var nonce = attributes.get('r');
        if (nonce == null || !nonce.startsWith(clientNonce)) throw new IOException("SCRAM: bad server nonce");
        var salt       = Base64.getDecoder().decode(attributes.get('s'));
        int iterations = Integer.parseInt(attributes.get('i'));
        try {
            var salted      = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
            var clientKey   = hmac(salted, "Client Key");
            var storedKey   = MessageDigest.getInstance("SHA-256").digest(clientKey);
            var withoutProof = "c=biws,r=" + nonce;
            var authMessage = clientFirstBare + "," + serverFirst + "," + withoutProof;
            var signature   = hmac(storedKey, authMessage);
            var proof       = new byte[clientKey.length];
            for (int i = 0; i < proof.length; i++) proof[i] = (byte) (clientKey[i] ^ signature[i]);
            return withoutProof + ",p=" + Base64.getEncoder().encodeToString(proof);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] md5(byte[] data) throws IOException {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (var p : parts) b.writeBytes(p);
        return b.toByteArray();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Prepare at connect, execute in binary
    // ------------------------------------------------------------------------------------------------------------------

    /** Parses every statement (name -> SQL with $n placeholders, with its parameter types) in one round trip. */
    void prepareAll(LinkedHashMap<String, Map.Entry<String, List<Type>>> statements) throws IOException {
        for (var e : statements.entrySet()) {
            var b = new Body().cstring(e.getKey()).cstring(e.getValue().getKey());
            var types = e.getValue().getValue();
            b.int2(types.size());
            for (var t : types) b.int4(t.oid);
            send('P', b);
        }
        sync();
        out.flush();
        String failure = null;
        while (true) {
            char type = read();
            if (type == 'E') failure = error();
            else if (type == 'Z') break;
        }
        if (failure != null) throw new IOException("Parse failed: " + failure);
    }

    /** Queues Bind (binary parameters, binary results) + Execute + Sync for a parsed statement; nothing is sent until {@link #flush}. */
    void queue(String statement, List<Type> types, Object[] values) throws IOException {
        queue(statement, types, values, true);
    }

    /**
     * As {@link #queue(String, List, Object[])}; without {@code sync} the call shares the next Sync (one implicit
     * transaction, one ReadyForQuery) with the calls queued after it, and {@link #receive} returns at that Sync with the
     * rows of all of them.
     */
    void queue(String statement, List<Type> types, Object[] values, boolean sync) throws IOException {
        var b = new Body().cstring("").cstring(statement);
        b.int2(1).int2(1);
        b.int2(values.length);
        for (int i = 0; i < values.length; i++) encode(b, types.get(i), values[i]);
        b.int2(1).int2(1);
        send('B', b);
        send('E', new Body().cstring("").int4(0));
        if (sync) sync();
    }

    void flush() throws IOException {
        out.flush();
    }

    /** Reads the replies of one queued call, up to and including its ReadyForQuery; throws on an ErrorResponse. */
    Result receive() throws IOException {
        int    rows = 0;
        long   affected = -1, first = Long.MIN_VALUE;
        String failure = null;
        while (true) {
            char type = read();
            switch (type) {
                case 'D' -> {
                    rows++;
                    int columns = int2(0), p = 2;
                    for (int c = 0; c < columns; c++) {
                        int len = int4(p);
                        p += 4;
                        if (len > 0) {
                            // copy the field out, as a driver keeps each row's values
                            var field = Arrays.copyOfRange(message, p, p + len);
                            if (c == 0 && len == 8) first = new DataInputStream(new ByteArrayInputStream(field)).readLong();
                            p += len;
                        }
                    }
                }
                case 'C' -> {
                    var tag   = new String(message, 0, messageLength - 1, StandardCharsets.UTF_8);
                    var space = tag.lastIndexOf(' ');
                    if (space > 0) {
                        try {
                            affected = Long.parseLong(tag.substring(space + 1));
                        } catch (NumberFormatException ignored) {
                            // a tag without a count
                        }
                    }
                }
                case 'E' -> failure = error();
                case 'Z' -> {
                    if (failure != null) throw new IOException(failure);
                    return new Result(rows, affected, first);
                }
                default -> {
                    // BindComplete, NoData, ParameterStatus, NoticeResponse, NotificationResponse
                }
            }
        }
    }

    /** A simple-protocol query; every row as text. */
    List<List<String>> simpleQuery(String sql) throws IOException {
        send('Q', new Body().cstring(sql));
        out.flush();
        var    rows    = new ArrayList<List<String>>();
        String failure = null;
        while (true) {
            char type = read();
            switch (type) {
                case 'D' -> {
                    int columns = int2(0), p = 2;
                    var row = new ArrayList<String>();
                    for (int c = 0; c < columns; c++) {
                        int len = int4(p);
                        p += 4;
                        row.add(len < 0 ? null : new String(message, p, len, StandardCharsets.UTF_8));
                        if (len > 0) p += len;
                    }
                    rows.add(row);
                }
                case 'E' -> failure = error();
                case 'Z' -> {
                    if (failure != null) throw new IOException(failure);
                    return rows;
                }
                default -> {
                }
            }
        }
    }

    private static void encode(Body b, Type type, Object value) {
        if (value == null) {
            b.int4(-1);
            return;
        }
        switch (type) {
            case INT8 -> b.int4(8).int8((Long) value);
            case INT4 -> b.int4(4).int4((Integer) value);
            case TEXT -> {
                var bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                b.int4(bytes.length);
                b.write(bytes, 0, bytes.length);
            }
            case INT8_ARRAY -> {
                var values = (long[]) value;
                if (values.length == 0) {
                    b.int4(12).int4(0).int4(0).int4(Type.INT8.oid);
                } else {
                    b.int4(20 + 12 * values.length).int4(1).int4(0).int4(Type.INT8.oid).int4(values.length).int4(1);
                    for (var v : values) b.int4(8).int8(v);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Framing
    // ------------------------------------------------------------------------------------------------------------------

    void sync() throws IOException {
        out.write('S');
        out.writeInt(4);
    }

    private void send(char type, Body b) throws IOException {
        out.write(type);
        out.writeInt(b.size() + 4);
        b.writeTo(out);
    }

    private char read() throws IOException {
        char type   = (char) in.readUnsignedByte();
        int  length = in.readInt() - 4;
        if (length > message.length) message = new byte[Math.max(length, message.length * 2)];
        in.readFully(message, 0, length);
        messageLength = length;
        return type;
    }

    private int int4(int at) {
        return ((message[at] & 0xff) << 24) | ((message[at + 1] & 0xff) << 16) | ((message[at + 2] & 0xff) << 8) | (message[at + 3] & 0xff);
    }

    private int int2(int at) {
        return ((message[at] & 0xff) << 8) | (message[at + 1] & 0xff);
    }

    /** SQLSTATE and message of the ErrorResponse in the buffer. */
    private String error() {
        String state = "?", text = "?";
        int    p     = 0;
        while (p < messageLength && message[p] != 0) {
            char field = (char) message[p++];
            int  end   = p;
            while (message[end] != 0) end++;
            var value = new String(message, p, end - p, StandardCharsets.UTF_8);
            if (field == 'C') state = value;
            if (field == 'M') text = value;
            p = end + 1;
        }
        return state + " " + text;
    }

    @Override
    public void close() throws IOException {
        try {
            out.write('X');
            out.writeInt(4);
            out.flush();
        } catch (IOException ignored) {
            // closing anyway
        } finally {
            socket.close();
        }
    }

    /** A message body under construction. */
    private static final class Body extends ByteArrayOutputStream {
        Body cstring(String s) {
            writeBytes(s.getBytes(StandardCharsets.UTF_8));
            write(0);
            return this;
        }

        Body bytes(byte[] b) {
            writeBytes(b);
            return this;
        }

        Body int2(int v) {
            write(v >>> 8);
            write(v);
            return this;
        }

        Body int4(int v) {
            write(v >>> 24);
            write(v >>> 16);
            write(v >>> 8);
            write(v);
            return this;
        }

        Body int8(long v) {
            int4((int) (v >>> 32));
            return int4((int) v);
        }
    }
}
