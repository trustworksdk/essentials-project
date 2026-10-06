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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.internal;

import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookkeeping of the middles of wide gaps a polling subscription awaits in memory only
 */
class GapMiddlesAwaitedInMemoryTest {
    private final AtomicLong                nanoTime = new AtomicLong(1_000);
    private final GapMiddlesAwaitedInMemory middles  = new GapMiddlesAwaitedInMemory(Duration.ofSeconds(10), nanoTime::get);

    @Test
    void a_middle_is_awaited_as_one_range_whatever_its_width() {
        assertThat(middles.isEmpty()).isTrue();

        middles.await(LongRange.between(5_004, 995_004));

        assertThat(middles.awaited()).containsExactly(LongRange.between(5_004, 995_004));
        assertThat(middles.isAwaited(5_003)).isFalse();
        assertThat(middles.isAwaited(5_004)).isTrue();
        assertThat(middles.isAwaited(500_000)).isTrue();
        assertThat(middles.isAwaited(995_004)).isTrue();
        assertThat(middles.isAwaited(995_005)).isFalse();
    }

    @Test
    void a_delivered_order_splits_its_range_and_is_never_awaited_again() {
        middles.await(LongRange.between(100, 200));

        assertThat(middles.delivered(150)).isTrue();
        assertThat(middles.awaited()).containsExactly(LongRange.between(100, 149), LongRange.between(151, 200));
        assertThat(middles.delivered(150)).as("delivered once").isFalse();
        assertThat(middles.isAwaited(150)).isFalse();

        // At the ends of a range: nothing empty is left behind
        assertThat(middles.delivered(100)).isTrue();
        assertThat(middles.delivered(200)).isTrue();
        assertThat(middles.awaited()).containsExactly(LongRange.between(101, 149), LongRange.between(151, 199));

        assertThat(middles.delivered(99)).isFalse();
        assertThat(middles.delivered(1_000)).isFalse();
    }

    @Test
    void a_range_of_one_order_is_gone_once_delivered() {
        middles.await(LongRange.only(42));

        assertThat(middles.delivered(42)).isTrue();
        assertThat(middles.isEmpty()).isTrue();
    }

    @Test
    void awaiting_a_range_again_adds_only_the_orders_above_every_order_awaited_before() {
        middles.await(LongRange.between(100, 200));
        middles.delivered(150);

        // A poll repeated from a read position that did not move: the same middle again - the delivered order must not
        // be awaited, and so delivered, again
        middles.await(LongRange.between(100, 200));
        assertThat(middles.awaited()).containsExactly(LongRange.between(100, 149), LongRange.between(151, 200));

        middles.await(LongRange.between(180, 260));
        assertThat(middles.awaited()).containsExactly(LongRange.between(100, 149), LongRange.between(151, 200), LongRange.between(201, 260));

        middles.await(LongRange.between(50, 99));
        assertThat(middles.awaited()).containsExactly(LongRange.between(100, 149), LongRange.between(151, 200), LongRange.between(201, 260));

        // Not after it was dropped either
        nanoTime.addAndGet(Duration.ofSeconds(10).toNanos());
        assertThat(middles.dropTimedOut()).hasSize(3);
        middles.await(LongRange.between(100, 260));
        assertThat(middles.isEmpty()).isTrue();
        middles.await(LongRange.between(300, 400));
        assertThat(middles.awaited()).containsExactly(LongRange.between(300, 400));
    }

    @Test
    void a_middle_is_dropped_once_its_timeout_has_passed_and_its_parts_with_it() {
        middles.await(LongRange.between(100, 200));
        middles.delivered(150);
        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos());
        middles.await(LongRange.between(1_000, 2_000));

        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos() - 1);
        assertThat(middles.dropTimedOut()).isEmpty();

        nanoTime.incrementAndGet();
        assertThat(middles.dropTimedOut()).containsExactly(LongRange.between(100, 149), LongRange.between(151, 200));
        assertThat(middles.awaited()).containsExactly(LongRange.between(1_000, 2_000));

        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(middles.dropTimedOut()).containsExactly(LongRange.between(1_000, 2_000));
        assertThat(middles.isEmpty()).isTrue();
        assertThat(middles.delivered(1_500)).isFalse();
    }

    @Test
    void a_middle_awaited_since_earlier_adds_only_the_orders_not_awaited_yet_and_times_out_from_then() {
        long since = nanoTime.get();
        middles.await(LongRange.between(100, 200));
        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos());

        // Handed on by a CDC subscription as it ends, awaited since it found it
        middles.await(LongRange.between(50, 300), since);
        assertThat(middles.awaited()).containsExactly(LongRange.between(50, 99), LongRange.between(100, 200), LongRange.between(201, 300));
        assertThat(middles.span()).contains(LongRange.between(50, 300));

        nanoTime.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(middles.dropTimedOut()).containsExactly(LongRange.between(50, 99), LongRange.between(100, 200), LongRange.between(201, 300));
        assertThat(middles.span()).isEmpty();
    }
}
