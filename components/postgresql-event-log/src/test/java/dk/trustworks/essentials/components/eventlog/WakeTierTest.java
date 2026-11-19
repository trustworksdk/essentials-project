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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.Test;
import org.postgresql.core.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests of tier 1: payload parsing and own-instance filtering, the notifier's coalescing, and the
 * upgrade guard of {@code PINNED_PEEK}'s reflection (a pgjdbc rename fails the build, not latency).
 */
class WakeTierTest {
    @Test
    void a_payload_names_the_type_to_wake_unless_it_is_our_own_or_has_no_type() {
        assertThat(WakeListener.typeToWake("abcd1234:Orders", "zzzz9999", true)).isEqualTo("Orders");
        assertThat(WakeListener.typeToWake("abcd1234:Orders", "abcd1234", true)).isNull();
        assertThat(WakeListener.typeToWake("abcd1234:Orders", "abcd1234", false)).isEqualTo("Orders");
        assertThat(WakeListener.typeToWake("abcd123:Orders", "abcd1234", true)).as("a prefix of our id is someone else").isEqualTo("Orders");
        assertThat(WakeListener.typeToWake("abcd1234:", "zzzz9999", true)).as("a kick").isNull();
        assertThat(WakeListener.typeToWake("Orders", "zzzz9999", true)).isNull();
        assertThat(WakeListener.typeToWake(":Orders", "zzzz9999", true)).isNull();
        assertThat(WakeListener.typeToWake(null, "zzzz9999", true)).isNull();
    }

    @Test
    void pgjdbc_still_has_the_fields_the_pinned_peek_writes() throws Exception {
        assertThat(QueryExecutorBase.class.getDeclaredField("pgStream").getType()).isEqualTo(PGStream.class);
        assertThat(WakeListener.PinnedPeek.nextCheckField().getType()).isEqualTo(long.class);
    }

    @Test
    void the_notifier_coalesces_what_arrives_while_a_send_is_in_flight() throws Exception {
        var sent      = new CopyOnWriteArrayList<List<String>>();
        var inFlight  = new CountDownLatch(1);
        var release   = new CountDownLatch(1);
        var notifier = new WakeNotifier("inst0001", (channel, payloads) -> {
            assertThat(channel).isEqualTo(WakeNotifier.CHANNEL);
            sent.add(List.copyOf(payloads));
            if (sent.size() == 1) {
                inFlight.countDown();
                release.await();
            }
        }, () -> TestSeams.NONE);
        notifier.add("A");
        assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();
        for (var i = 0; i < 100; i++) {
            notifier.add("B");
            notifier.add("C");
        }
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 2);
        assertThat(sent.get(0)).containsExactly("inst0001:A");
        assertThat(sent.get(1)).containsExactlyInAnyOrder("inst0001:B", "inst0001:C");
        notifier.stop();
        await().atMost(Duration.ofSeconds(10)).until(() -> !notifier.isRunning());
        notifier.add("D");
        await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 3);
        assertThat(sent.get(2)).containsExactly("inst0001:D");
    }

    @Test
    void a_failed_send_is_dropped_not_retried() {
        var attempts = new CopyOnWriteArrayList<List<String>>();
        var notifier = new WakeNotifier("inst0001", (channel, payloads) -> {
            attempts.add(List.copyOf(payloads));
            throw new java.sql.SQLException("down");
        }, () -> TestSeams.NONE);
        notifier.add("A");
        await().atMost(Duration.ofSeconds(10)).until(() -> notifier.failures.sum() == 1);
        notifier.add("B");
        await().atMost(Duration.ofSeconds(10)).until(() -> notifier.failures.sum() == 2);
        assertThat(attempts).containsExactly(List.of("inst0001:A"), List.of("inst0001:B"));
        notifier.stop();
    }

    @Test
    void the_notifier_unions_what_arrives_inside_its_send_interval_into_the_next_send() throws Exception {
        var sent     = new CopyOnWriteArrayList<List<String>>();
        var sentAt   = new CopyOnWriteArrayList<Long>();
        var interval = Duration.ofMillis(200);
        var notifier = new WakeNotifier("inst0001", (channel, payloads) -> {
            sentAt.add(System.nanoTime());
            sent.add(List.copyOf(payloads));
        }, () -> TestSeams.NONE, interval);
        notifier.add("A");
        await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 1);
        notifier.add("B");
        notifier.add("C");
        notifier.add("B");
        await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 2);
        assertThat(sent.get(1)).as("one send for everything inside the interval").containsExactlyInAnyOrder("inst0001:B", "inst0001:C");
        assertThat(sentAt.get(1) - sentAt.get(0)).as("not before the interval ended").isGreaterThanOrEqualTo(interval.toNanos());
        Thread.sleep(interval.toMillis() * 2);
        assertThat(sent).hasSize(2);
        notifier.stop();
    }

    @Test
    void a_stop_sends_what_is_pending_without_waiting_for_the_interval() throws Exception {
        var sent     = new CopyOnWriteArrayList<List<String>>();
        var notifier = new WakeNotifier("inst0001", (channel, payloads) -> sent.add(List.copyOf(payloads)), () -> TestSeams.NONE, Duration.ofSeconds(30));
        notifier.add("A");
        await().atMost(Duration.ofSeconds(10)).until(() -> sent.size() == 1);
        notifier.add("B");
        notifier.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> !notifier.isRunning());
        assertThat(sent).containsExactly(List.of("inst0001:A"), List.of("inst0001:B"));
    }
}
