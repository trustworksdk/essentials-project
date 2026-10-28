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

package dk.trustworks.essentials.components.queue.postgresql;

import dk.trustworks.essentials.components.foundation.messaging.queue.QueueName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static dk.trustworks.essentials.components.queue.postgresql.OrderedClaimStatementChooser.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The poll timings are made up: each test feeds the chooser the elapsed time a statement would take for the queue's
 * shape, and checks which statement it picks
 */
class OrderedClaimStatementChooserTest {
    private static final long THRESHOLD = Duration.ofMillis(10).toNanos();
    private static final int  LIMIT     = 64;

    private final OrderedClaimStatementChooser chooser = new OrderedClaimStatementChooser(QueueName.of("Queue"));

    /**
     * How long each statement takes, and how much the default claims
     */
    private record Shape(long defaultMillis, long headScanMillis, int defaultClaims) {
    }

    /**
     * Polls {@code polls} times and returns how many of them used the head scan
     */
    private int poll(Shape shape, int polls) {
        var headScans = 0;
        for (var i = 0; i < polls; i++) {
            var byHeadScan = chooser.byHeadScan();
            if (byHeadScan) {
                headScans++;
                chooser.record(true, Duration.ofMillis(shape.headScanMillis).toNanos(), shape.defaultClaims, LIMIT, THRESHOLD);
            } else {
                chooser.record(false, Duration.ofMillis(shape.defaultMillis).toNanos(), shape.defaultClaims, LIMIT, THRESHOLD);
            }
        }
        return headScans;
    }

    @Test
    void a_database_far_away_does_not_make_the_statements_alternate() {
        // Many keys and a short claim, on a database 15 ms away: every poll is slow, and the head scan far slower.
        // Switching on each slow poll ran the head scan every other poll
        var headScans = poll(new Shape(20, 130, 5), 10_000);

        // One switch after the first slow polls, then probes at doubling intervals up to every 1 024 slow polls: about
        // 15 head scans where switching on every slow poll ran 5 000
        assertThat(headScans).isLessThanOrEqualTo(15);
        assertThat(chooser.byHeadScan()).isFalse();
    }

    @Test
    void one_slow_poll_does_not_switch() {
        var fast = new Shape(2, 50, 5);
        poll(fast, 10);
        // A GC pause
        chooser.record(false, Duration.ofMillis(200).toNanos(), 5, LIMIT, THRESHOLD);
        assertThat(chooser.byHeadScan()).isFalse();
        poll(fast, 10);
        assertThat(chooser.byHeadScan()).isFalse();
    }

    @Test
    void few_long_keys_switch_to_the_head_scan_and_many_keys_switch_back() {
        // Few keys with many messages each: the default probes every candidate and claims short
        poll(new Shape(80, 3, 20), SLOW_POLLS_BEFORE_SWITCH);
        assertThat(chooser.byHeadScan()).isTrue();
        poll(new Shape(80, 3, 20), 100);
        assertThat(chooser.byHeadScan()).as("the head scan is fast, so it stays").isTrue();

        // Many keys: the head scan is slow. The default's last measurement (80 ms) is older than the shape change, and
        // faster than the head scan now, so it is tried
        poll(new Shape(2, 114, LIMIT), SLOW_POLLS_BEFORE_SWITCH);
        assertThat(chooser.byHeadScan()).isFalse();
        poll(new Shape(2, 114, LIMIT), 100);
        assertThat(chooser.byHeadScan()).as("the default is fast, so it stays").isFalse();
    }

    @Test
    void a_stale_measurement_does_not_pin_the_slower_statement() {
        // Many keys: the head scan was measured at 300 ms
        poll(new Shape(30, 300, 5), SLOW_POLLS_BEFORE_SWITCH + 1);
        assertThat(chooser.byHeadScan()).isFalse();

        // The queue's backlog moves onto few keys: the default gets slower, but stays below the head scan's stale
        // 300 ms, so only a probe finds that the head scan is now fast
        var fewKeys = new Shape(120, 3, 20);
        poll(fewKeys, MAX_PROBE_INTERVAL + 1);
        assertThat(chooser.byHeadScan()).isTrue();
        poll(fewKeys, 100);
        assertThat(chooser.byHeadScan()).isTrue();
    }

    @Test
    void a_full_default_claim_is_never_slow() {
        // Large payloads make the default slow, but a full claim means there were heads enough
        poll(new Shape(40, 3, LIMIT), 1_000);
        assertThat(chooser.byHeadScan()).isFalse();
    }

    @Test
    void probes_back_off_but_never_stop() {
        // Both statements slow, the head scan slower: each lost probe doubles the interval up to the maximum
        var headScans = poll(new Shape(20, 130, 5), 3 + MAX_PROBE_INTERVAL * 10);
        assertThat(headScans).isGreaterThanOrEqualTo(10);
    }

    @Test
    void only_a_probe_runs_under_a_timeout_of_the_poll_that_caused_the_switch() {
        assertThat(chooser.probeTimeoutNanos(THRESHOLD)).isZero();
        for (var i = 0; i < SLOW_POLLS_BEFORE_SWITCH; i++) {
            chooser.record(false, Duration.ofMillis(40).toNanos(), 0, LIMIT, THRESHOLD);
        }
        assertThat(chooser.byHeadScan()).isTrue();
        assertThat(chooser.probeTimeoutNanos(THRESHOLD)).isEqualTo(Duration.ofMillis(40).toNanos());
        assertThat(chooser.probeTimeoutNanos(Duration.ofMillis(60).toNanos())).as("never below the slow threshold").isEqualTo(Duration.ofMillis(60).toNanos());

        // The probe was cancelled at its timeout: it took the timeout and claimed nothing
        chooser.record(true, chooser.probeTimeoutNanos(THRESHOLD), 0, LIMIT, THRESHOLD);
        assertThat(chooser.byHeadScan()).as("switched back").isFalse();
        assertThat(chooser.probeTimeoutNanos(THRESHOLD)).isZero();
    }
}
