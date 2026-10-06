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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.types.LongRange.between;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the middles of wide gaps awaited in memory only are handed from one subscribe of a subscriber to the next
 */
class GapMiddlesAwaitedAcrossSubscribesTest {
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("subscriber");
    private static final AggregateType ORDERS     = AggregateType.of("Orders");
    private static final Duration      TIMEOUT    = Duration.ofSeconds(10);

    private final AtomicLong                        nanoTime         = new AtomicLong(1_000);
    private final GapMiddlesAwaitedAcrossSubscribes acrossSubscribes = new GapMiddlesAwaitedAcrossSubscribes(nanoTime::get);

    @Test
    void the_next_subscribe_awaits_the_middles_below_where_it_starts_and_drops_those_it_reads_in_order() {
        var first = subscribe(1);
        first.await(between(100, 200));
        first.await(between(1_000, 2_000));
        first.await(between(5_000, 6_000));
        acrossSubscribes.subscribeEnded(first);

        var next = subscribe(1_500);

        assertThat(next.awaited()).containsExactly(between(100, 200), between(1_000, 1_499));
        // What it finds from where it starts is awaited again - not cut off by what the first subscribe awaited
        next.await(between(1_600, 1_700));
        assertThat(next.awaited()).containsExactly(between(100, 200), between(1_000, 1_499), between(1_600, 1_700));
    }

    @Test
    void a_middle_keeps_its_original_timeout_across_subscribes() {
        var first = subscribe(1);
        first.await(between(100, 200));
        advanceClock(Duration.ofSeconds(6));
        acrossSubscribes.subscribeEnded(first);

        var next = subscribe(300);
        next.await(between(400, 500));
        assertThat(next.awaited()).containsExactly(between(100, 200), between(400, 500));

        advanceClock(Duration.ofSeconds(4));
        assertThat(next.dropTimedOut()).as("10 s after the first subscribe started to await it").containsExactly(between(100, 200));
        assertThat(next.awaited()).containsExactly(between(400, 500));

        advanceClock(Duration.ofSeconds(6));
        assertThat(next.dropTimedOut()).containsExactly(between(400, 500));
    }

    @Test
    void a_subscribe_that_ended_awaits_nothing_new_nor_stops_awaiting_what_it_read_but_its_subscriber_being_done_holds() {
        var first = subscribe(1);
        first.await(between(100, 200));
        acrossSubscribes.subscribeEnded(first);
        var next = subscribe(300);

        // Its last poll, finishing after it ended: what it found there the next subscribe reads itself, from where it starts
        first.await(between(400, 500));
        assertThat(first.delivered(150)).isFalse();
        assertThat(next.awaited()).containsExactly(between(100, 200));

        // The subscriber acknowledged an event it was handed before the end: it is not owed to the next subscribe
        first.handled(List.of(event(150)));
        assertThat(next.awaited()).containsExactly(between(100, 149), between(151, 200));
    }

    @Test
    void a_subscribe_that_starts_before_the_previous_one_ended_takes_the_middles_over() {
        var first = subscribe(1);
        first.await(between(100, 200));
        var next = subscribe(300);

        first.await(between(400, 500));
        assertThat(next.awaited()).containsExactly(between(100, 200));

        // The first one ending later does not end the next one's hold
        acrossSubscribes.subscribeEnded(first);
        next.await(between(600, 700));
        assertThat(next.delivered(150)).isTrue();
        assertThat(next.awaited()).containsExactly(between(100, 149), between(151, 200), between(600, 700));
    }

    @Test
    void other_subscribers_and_aggregate_types_have_middles_of_their_own() {
        var first = subscribe(1);
        first.await(between(100, 200));
        acrossSubscribes.subscribeEnded(first);

        assertThat(acrossSubscribes.subscribe(SubscriberId.of("other"), ORDERS, 300, TIMEOUT).isEmpty()).isTrue();
        assertThat(acrossSubscribes.subscribe(SUBSCRIBER, AggregateType.of("Products"), 300, TIMEOUT).isEmpty()).isTrue();
        assertThat(subscribe(300).awaited()).containsExactly(between(100, 200));
    }

    @Test
    void forgotten_middles_are_not_awaited_by_the_next_subscribe_nor_by_one_still_holding_them() {
        var first = subscribe(1);
        first.await(between(100, 200));
        acrossSubscribes.subscribeEnded(first);
        var holding = subscribe(300);

        acrossSubscribes.forget(SUBSCRIBER, ORDERS);

        assertThat(holding.isEmpty()).isTrue();
        holding.await(between(400, 500));
        assertThat(holding.isEmpty()).as("awaits nothing new either").isTrue();
        acrossSubscribes.subscribeEnded(holding);
        assertThat(subscribe(600).isEmpty()).isTrue();
    }

    @Test
    void an_entry_is_kept_only_while_a_subscribe_holds_it_or_a_middle_is_awaited() {
        var idle = subscribe(1);
        assertThat(acrossSubscribes.isKeptFor(SUBSCRIBER, ORDERS)).isTrue();
        acrossSubscribes.subscribeEnded(idle);
        assertThat(acrossSubscribes.isKeptFor(SUBSCRIBER, ORDERS)).as("nothing awaited").isFalse();

        var awaiting = subscribe(1);
        awaiting.await(between(100, 200));
        acrossSubscribes.subscribeEnded(awaiting);
        assertThat(acrossSubscribes.isKeptFor(SUBSCRIBER, ORDERS)).isTrue();

        // No subscribe takes them over before they time out
        advanceClock(TIMEOUT);
        acrossSubscribes.forgetTimedOut();
        assertThat(acrossSubscribes.isKeptFor(SUBSCRIBER, ORDERS)).isFalse();
        assertThat(acrossSubscribes.size()).isZero();
    }

    @Test
    void an_entry_whose_middles_were_all_handled_is_forgotten_when_the_subscribe_ends() {
        var subscribe = subscribe(1);
        subscribe.await(between(100, 100));
        subscribe.handled(List.of(event(100)));
        acrossSubscribes.subscribeEnded(subscribe);

        assertThat(acrossSubscribes.isKeptFor(SUBSCRIBER, ORDERS)).isFalse();
    }

    private GapMiddlesAwaitedInMemory subscribe(long fromInclusive) {
        return acrossSubscribes.subscribe(SUBSCRIBER, ORDERS, fromInclusive, TIMEOUT);
    }

    private void advanceClock(Duration duration) {
        nanoTime.addAndGet(duration.toNanos());
    }

    private static PersistedEvent event(long globalOrder) {
        return PersistedEvent.from(EventId.random(),
                                   ORDERS,
                                   OrderId.random(),
                                   new EventJSON(EssentialsJSONEventSerializers.create(), EventType.of("TestEvent"), "{}"),
                                   EventOrder.of(1L),
                                   EventRevision.of(1),
                                   GlobalEventOrder.of(globalOrder),
                                   new EventMetaDataJSON(EssentialsJSONEventSerializers.create(), "", ""),
                                   OffsetDateTime.now(),
                                   Optional.empty(),
                                   Optional.empty(),
                                   Optional.empty());
    }
}
