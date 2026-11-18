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

package dk.trustworks.essentials.examples.trading.integration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.HexFormat;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Verifies the simulators' webhook signature: {@code X-Signature} is {@code sha256=<hex>} of
 * HMAC-SHA256(secret, {@code <X-Signature-Timestamp>.<raw body>}). During a secret rotation the header carries two
 * comma-separated values, and either may match.
 * <p>
 * The timestamp is unix seconds of the delivery attempt on the sender's <em>real</em> clock, not its virtual domain
 * clock, so it is checked against this JVM's wall clock.
 * <p>
 * Always verify the body exactly as received: re-serialized JSON does not match.
 */
public final class WebhookSignatureVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX    = "sha256=";

    private final SecretKeySpec key;
    private final Duration      tolerance;
    private final Clock         clock;

    public WebhookSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        requireNonNull(secret, "No secret provided");
        // The simulators trim a secret read from a file; trim here too, or a trailing newline fails every delivery.
        var trimmed = secret.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Webhook secret is blank");
        }
        this.key = new SecretKeySpec(trimmed.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        this.tolerance = requireNonNull(tolerance, "No tolerance provided");
        this.clock = requireNonNull(clock, "No clock provided");
    }

    public boolean verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > tolerance.toSeconds()) {
            return false;
        }
        var expected = expectedSignature(timestampHeader.strip(), rawBody);
        var matches  = false;
        for (var value : signatureHeader.split(",")) {
            // Compare every value, without short-circuiting, so timing does not reveal which one matched.
            matches |= MessageDigest.isEqual(expected, value.strip().getBytes(StandardCharsets.US_ASCII));
        }
        return matches;
    }

    /**
     * The {@code X-Signature} value a sender with this secret would produce. Public for tests that play the sender.
     */
    public String sign(String timestamp, byte[] rawBody) {
        return new String(expectedSignature(timestamp, rawBody), StandardCharsets.US_ASCII);
    }

    private byte[] expectedSignature(String timestamp, byte[] rawBody) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return (PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody))).getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
