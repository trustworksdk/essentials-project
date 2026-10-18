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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinned to the known vector the simulators ship with their own receiver samples ({@code examples/receivers/Verify.java}
 * in the exchange-simulator), so this verifier and the sender cannot drift apart unnoticed.
 */
class WebhookSignatureVerifierTest {
    private static final String SECRET    = "whsec_test_0123456789";
    private static final String TIMESTAMP = "1760443200";
    private static final byte[] BODY      = "{\"eventId\":\"e-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final String VALID     = "sha256=cde596a7a23fd7c03b27e1cf91e6fba42e15936f765508360d753019cd935bc1";

    private static WebhookSignatureVerifier verifierAt(String secret, long epochSecond) {
        return new WebhookSignatureVerifier(secret, Duration.ofMinutes(5), Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
    }

    private final WebhookSignatureVerifier verifier = verifierAt(SECRET, Long.parseLong(TIMESTAMP));

    @Test
    void accepts_the_senders_known_signature() {
        assertThat(verifier.verify(TIMESTAMP, VALID, BODY)).isTrue();
        assertThat(verifier.sign(TIMESTAMP, BODY)).isEqualTo(VALID);
    }

    @Test
    void accepts_either_value_during_a_secret_rotation() {
        assertThat(verifier.verify(TIMESTAMP, "sha256=00, " + VALID, BODY)).isTrue();
    }

    @Test
    void trims_a_secret_read_from_a_file_as_the_sender_does() {
        assertThat(verifierAt(SECRET + "\n", Long.parseLong(TIMESTAMP)).verify(TIMESTAMP, VALID, BODY)).isTrue();
    }

    @Test
    void rejects_a_changed_body() {
        assertThat(verifier.verify(TIMESTAMP, VALID, "{\"eventId\":\"e-1\"} ".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void rejects_another_secret() {
        assertThat(verifierAt("another-secret-0123", Long.parseLong(TIMESTAMP)).verify(TIMESTAMP, VALID, BODY)).isFalse();
    }

    @Test
    void rejects_a_timestamp_outside_the_tolerance() {
        assertThat(verifierAt(SECRET, Long.parseLong(TIMESTAMP) + 3600).verify(TIMESTAMP, VALID, BODY)).isFalse();
    }

    @Test
    void rejects_missing_or_malformed_headers() {
        assertThat(verifier.verify(null, VALID, BODY)).isFalse();
        assertThat(verifier.verify(TIMESTAMP, null, BODY)).isFalse();
        assertThat(verifier.verify("not-a-number", VALID, BODY)).isFalse();
    }
}
