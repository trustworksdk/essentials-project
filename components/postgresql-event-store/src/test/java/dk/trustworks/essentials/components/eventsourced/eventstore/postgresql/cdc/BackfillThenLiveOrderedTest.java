/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.WalReplicationWithEssentialsAggregateWal2JsonIT.ORDERS;
import static org.assertj.core.api.Assertions.assertThat;

public class BackfillThenLiveOrderedTest {

    /**
     * What this test proves
     * <p>
     * No lost events
     * <p>
     * Live is subscribed before backfill emits anything
     * <p>
     * No reordering
     * <p>
     * Live publishes 5 → 4
     * <p>
     * Subscriber sees 4 → 5
     * <p>
     * Correct boundary
     * <p>
     * Backfill finishes at 3
     * <p>
     * Live starts exactly at 4
     */
    @Test
    void ordered_handoff_buffers_live_until_backfill_done_and_emits_in_strict_global_order() {
        var cdcBus = new CdcEventBus();

        // Controlled backfill
        Sinks.Many<PersistedEvent> backfillSink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<PersistedEvent> backfill = backfillSink.asFlux();

        // Live from bus
        Flux<PersistedEvent> live = cdcBus.fluxForAggregate(AggregateType.of("Orders"));

        // head=3 => live should start at 4, but we'll publish 5 then 4 before backfill completes
        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill, live, 3, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered.take(5))
                    .then(() -> {
                        // publish out-of-order live BEFORE backfill completes
                        cdcBus.publish(List.of(pe(5)));
                        cdcBus.publish(List.of(pe(4)));

                        // now emit backfill 1..3
                        backfillSink.tryEmitNext(pe(1));
                        backfillSink.tryEmitNext(pe(2));
                        backfillSink.tryEmitNext(pe(3));
                        backfillSink.tryEmitComplete();
                    })
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(1))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(2))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(3))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(4))
                    .assertNext(e -> assertThat(e.globalEventOrder().longValue()).isEqualTo(5))
                    .verifyComplete();
    }

    @Test
    void ordered_backfill_then_live_reorders_out_of_order_live_events() {
        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(2), pe(3));

        // out of order: 6 arrives before 4/5
        Flux<PersistedEvent> live = Flux.just(pe(6), pe(4), pe(5));

        Flux<PersistedEvent> ordered =
                CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill, live, 3, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 1L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 2L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 3L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 4L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 5L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 6L)
                    .verifyComplete();
    }

    @Test
    void ordered_backfill_then_live_does_not_miss_headPlusOne_emitted_during_backfill() {
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().multicast().onBackpressureBuffer();

        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(2), pe(3))
                                            .delayElements(Duration.ofMillis(50))
                                            .doOnSubscribe(s -> {
                                                // emit head+1 during backfill (before backfill completes)
                                                liveSink.tryEmitNext(pe(4));
                                            });

        Flux<PersistedEvent> live = liveSink.asFlux();

        Flux<PersistedEvent> ordered =
                CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill, live, 3, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered.take(4))
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 1L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 2L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 3L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 4L) // MUST NOT be missed
                    .verifyComplete();
    }

    /**
     * Live demand must be capped by backpressureBufferSize while backfill is still running.
     * Without drain firing (drain is gated by backfillDone), BaseSubscriber must not refill demand —
     * this is what keeps the in-memory buffer bounded regardless of how fast live events arrive.
     */
    @Test
    void live_demand_is_bounded_by_backpressureBufferSize_while_backfill_is_running() {
        var props = new CdcProperties.CdcEventBusProperties();
        props.setBackpressureBufferSize(4);

        Sinks.Many<PersistedEvent> backfillSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();

        AtomicLong totalRequested = new AtomicLong(0);
        Flux<PersistedEvent> live = liveSink.asFlux().doOnRequest(totalRequested::addAndGet);

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(
                backfillSink.asFlux(), live, 3, props);

        Disposable sub = ordered.subscribe();

        // Publish many more live events than bufferSize allows
        for (int go = 4; go < 50; go++) {
            liveSink.tryEmitNext(pe(go));
        }

        // Backfill not yet complete → drain is a no-op → no demand refills.
        // Total upstream demand must remain exactly bufferSize.
        assertThat(totalRequested.get()).isEqualTo(4L);

        sub.dispose();
    }

    /**
     * Even with a tight bound (bufferSize=4), a large burst of in-order live events must be
     * fully delivered in strict global order once backfill completes. This verifies that the
     * BaseSubscriber demand refills via drain → request(drained) cycle correctly, without
     * hitting FAIL_OVERFLOW on the bounded ordered-live sink.
     */
    @Test
    void bounded_buffer_delivers_large_live_burst_in_order_after_backfill_completes() throws Exception {
        var props = new CdcProperties.CdcEventBusProperties();
        props.setBackpressureBufferSize(4);

        Sinks.Many<PersistedEvent> backfillSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(
                backfillSink.asFlux(), liveSink.asFlux(), 3, props);

        List<Long> collected = new CopyOnWriteArrayList<>();
        AtomicLong errorCount = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);

        // Pre-queue 100 live events — they sit in liveSink until liveSub requests
        int totalLive = 100;
        for (int go = 4; go < 4 + totalLive; go++) {
            liveSink.tryEmitNext(pe(go));
        }
        liveSink.tryEmitComplete();

        ordered.map(e -> e.globalEventOrder().longValue())
               .subscribe(
                       collected::add,
                       err -> {
                           errorCount.incrementAndGet();
                           done.countDown();
                       },
                       done::countDown
                         );

        // Complete backfill → unblocks drain → emits accumulated live, refills demand, repeats
        backfillSink.tryEmitNext(pe(1));
        backfillSink.tryEmitNext(pe(2));
        backfillSink.tryEmitNext(pe(3));
        backfillSink.tryEmitComplete();

        assertThat(done.await(5, TimeUnit.SECONDS)).as("pipeline completes").isTrue();
        assertThat(errorCount.get()).as("no overflow / fail-fast errors").isZero();
        assertThat(collected).hasSize(3 + totalLive);
        for (int i = 0; i < collected.size(); i++) {
            assertThat(collected.get(i)).isEqualTo((long) (i + 1));
        }
    }

    private static PersistedEvent pe(long globalOrder) {
        return PersistedEvent.from(
                EventId.random(),
                ORDERS,
                OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89"),
                new EventJSON(new JacksonJSONEventSerializer(new ObjectMapper()), EventType.of("TestEvent"), """
                                                                                                             {"type":"TestEvent","globalOrder":%d}
                                                                                                             """.formatted(globalOrder)),
                EventOrder.of(1L),
                EventRevision.of(1),
                GlobalEventOrder.of(globalOrder),
                new EventMetaDataJSON(new JacksonJSONEventSerializer(new ObjectMapper()), "", ""),
                OffsetDateTime.now(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
                                  );
    }

}
