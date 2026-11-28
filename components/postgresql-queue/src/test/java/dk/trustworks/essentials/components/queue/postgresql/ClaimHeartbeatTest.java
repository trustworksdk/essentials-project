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

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim heartbeat's selection, fencing and cap, against a fake clock and a fake refresher - no database
 */
class ClaimHeartbeatTest {
    private static final Duration  INTERVAL  = Duration.ofSeconds(10);
    private static final Duration  MAX       = Duration.ofSeconds(100);
    private static final QueueName QUEUE     = QueueName.of("Q");
    private static final Instant   CLAIMED   = Instant.parse("2026-11-28T10:00:00.123456Z");

    private final AtomicLong                        nanos     = new AtomicLong(1_000_000_000L);
    private final List<Map<QueueEntryId, Instant>>  calls     = new ArrayList<>();
    private final Set<QueueEntryId>                 stillHeld = new HashSet<>();
    private final ClaimHeartbeat                    heartbeat = new ClaimHeartbeat(INTERVAL, MAX,
                                                                                   (claims, now) -> {
                                                                                       calls.add(Map.copyOf(claims));
                                                                                       var refreshed = new HashSet<>(claims.keySet());
                                                                                       refreshed.retainAll(stillHeld);
                                                                                       return refreshed;
                                                                                   },
                                                                                   nanos::get,
                                                                                   () -> Instant.ofEpochSecond(nanos.get() / 1_000_000_000L, nanos.get() % 1_000_000_000L),
                                                                                   "test");

    @Test
    void a_claim_younger_than_the_interval_is_not_written() {
        var id = claim("1");
        advance(INTERVAL.minusMillis(1));

        assertThat(heartbeat.tick()).isZero();
        assertThat(calls).isEmpty();
        assertThat(heartbeat.isTracked(id)).isTrue();
    }

    @Test
    void a_released_claim_is_never_written() {
        var id = claim("1");
        heartbeat.release(id);
        advance(INTERVAL);

        assertThat(heartbeat.tick()).isZero();
        assertThat(calls).isEmpty();
    }

    @Test
    void due_claims_are_refreshed_in_one_call_fenced_on_the_last_written_timestamp() {
        var a = claim("a");
        var b = claim("b");
        advance(INTERVAL);

        assertThat(heartbeat.tick()).isEqualTo(2);
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)).isEqualTo(Map.of(a, CLAIMED, b, CLAIMED));

        // Not due again until another interval has passed since the write
        advance(INTERVAL.minusMillis(1));
        assertThat(heartbeat.tick()).isZero();
        advance(Duration.ofMillis(1));
        assertThat(heartbeat.tick()).isEqualTo(2);
        // Fenced on the value the previous refresh wrote (micros precision), not on the original claim
        var firstRefresh = calls.get(1).get(a);
        assertThat(firstRefresh).isNotEqualTo(CLAIMED);
        assertThat(firstRefresh.getNano() % 1_000).isZero();
    }

    @Test
    void a_claim_the_refresh_no_longer_finds_is_dropped() {
        var kept = claim("kept");
        var lost = claim("lost");
        stillHeld.remove(lost);
        advance(INTERVAL);

        assertThat(heartbeat.tick()).isEqualTo(1);
        assertThat(heartbeat.isTracked(kept)).isTrue();
        assertThat(heartbeat.isTracked(lost)).isFalse();
    }

    @Test
    void a_claim_past_the_maximum_is_no_longer_refreshed() {
        var id = claim("1");
        for (int i = 0; i < 9; i++) {
            advance(INTERVAL);
            assertThat(heartbeat.tick()).isEqualTo(1);
        }
        advance(INTERVAL);

        assertThat(heartbeat.tick()).isZero();
        assertThat(heartbeat.isTracked(id)).isFalse();
        assertThat(calls).hasSize(9);
    }

    @Test
    void a_disabled_heartbeat_tracks_nothing() {
        var disabled = new ClaimHeartbeat(INTERVAL, Duration.ZERO, (claims, now) -> Set.of(), nanos::get, Instant::now, "off");
        disabled.track(message("1"));

        assertThat(disabled.trackedCount()).isZero();
        disabled.start();
        assertThat(disabled.isRunning()).isFalse();
    }

    @Test
    void stop_ends_the_thread_and_forgets_every_claim() {
        claim("1");
        heartbeat.start();
        assertThat(heartbeat.isRunning()).isTrue();

        heartbeat.stop();

        assertThat(heartbeat.isRunning()).isFalse();
        assertThat(heartbeat.trackedCount()).isZero();
    }

    private QueueEntryId claim(String id) {
        var message = message(id);
        stillHeld.add(message.getId());
        heartbeat.track(message);
        return message.getId();
    }

    private static QueuedMessage message(String id) {
        return DefaultQueuedMessage.builder()
                                   .setId(QueueEntryId.of(id))
                                   .setQueueName(QUEUE)
                                   .setMessage(Message.of("payload"))
                                   .setAddedTimestamp(OffsetDateTime.ofInstant(CLAIMED, ZoneOffset.UTC))
                                   .setDeliveryTimestamp(OffsetDateTime.ofInstant(CLAIMED, ZoneOffset.UTC))
                                   .setBeingDelivered(true)
                                   .build();
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }
}
