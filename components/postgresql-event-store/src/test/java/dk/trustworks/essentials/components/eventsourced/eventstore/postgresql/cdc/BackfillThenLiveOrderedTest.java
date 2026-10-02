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

import tools.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.stream.LongStream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.WalReplicationWithEssentialsAggregateWal2JsonIT.ORDERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    /**
     * The ordered hand-over may only take from the live source what its subscriber has actually taken from it. It used
     * to re-request from the live source as soon as the drain moved events into its bounded ordered-live sink, whether
     * or not anyone consumed them. A subscriber that pauses its demand - a batched subscriber whose batch handler is
     * busy on its own thread, a handler in a retry backoff - then let the live source keep pouring events into that
     * sink until it overflowed, and the {@link CdcBusOverflowException} ended the subscription's flux: the subscriber
     * got an error it does not handle and silently received nothing more.
     */
    @Test
    void a_subscriber_that_pauses_its_demand_holds_back_the_live_source_instead_of_overflowing_the_hand_over() {
        var props = new CdcProperties.CdcEventBusProperties();
        props.setBackpressureBufferSize(4);
        // Any overflow of the ordered-live sink fails at once instead of after its back-off
        props.setOverflowMaxRetries(0);
        int  totalLive         = 200;
        long lastGlobalOrder   = 3 + totalLive;
        var  requestedFromLive = new AtomicLong();
        // As in production, the backfill and the live source each emit on a thread of their own, so the subscriber's
        // demand is the only thing that can pace the live source
        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(2), pe(3)).subscribeOn(Schedulers.newSingle("test-backfill", true));
        Flux<PersistedEvent> live = Flux.range(4, totalLive)
                                        .map(globalOrder -> pe(globalOrder))
                                        .doOnRequest(requestedFromLive::addAndGet)
                                        .subscribeOn(Schedulers.newSingle("test-live", true));

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill, live, 3, props);

        var received = new CopyOnWriteArrayList<Long>();
        var failure  = new AtomicReference<Throwable>();
        var subscriber = new BaseSubscriber<PersistedEvent>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                // The backfill and the first two live events, then the handler is "busy"
                request(5);
            }

            @Override
            protected void hookOnNext(PersistedEvent event) {
                received.add(event.globalEventOrder().longValue());
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                failure.set(throwable);
            }
        };
        ordered.subscribe(subscriber);

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() == 5 || failure.get() != null);
        // Gives the live source every chance to overrun the paused subscriber
        await().pollDelay(Duration.ofMillis(500)).until(() -> true);
        assertThat(failure.get()).as("the paused subscriber's flux is still alive").isNull();
        assertThat(received).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(requestedFromLive.get()).as("the live source is held back while the subscriber is paused").isLessThan(totalLive);

        // The handler is done: everything follows, once and in order
        subscriber.request(Long.MAX_VALUE);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= lastGlobalOrder || failure.get() != null);
        assertThat(failure.get()).isNull();
        assertThat(received).containsExactlyElementsOf(LongStream.rangeClosed(1, lastGlobalOrder).boxed().toList());
        subscriber.dispose();
    }

    /**
     * Same accounting, other symptom: a backfill that completes while it is being subscribed (nothing to back-fill) runs
     * the drain before the merge has subscribed the ordered-live sink. Every event the live source was asked for beyond
     * what that sink's queue holds was emitted to a sink without a subscriber - {@code FAIL_ZERO_SUBSCRIBER}, which the
     * emitter drops at DEBUG - and the subscriber silently skipped them.
     */
    @Test
    void a_backfill_that_completes_before_the_ordered_live_sink_is_subscribed_loses_no_live_event() {
        var props = new CdcProperties.CdcEventBusProperties();
        props.setBackpressureBufferSize(4);
        int totalLive = 200;

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(
                Flux.just(pe(1), pe(2), pe(3)),
                Flux.range(4, totalLive).map(globalOrder -> pe(globalOrder)),
                3,
                props);

        StepVerifier.create(ordered.map(event -> event.globalEventOrder().longValue()))
                    .expectNextSequence(LongStream.rangeClosed(1, 3 + totalLive).boxed().toList())
                    .verifyComplete();
    }

    /**
     * Race fix: the head snapshot must be taken only AFTER the live source has been subscribed, never
     * before. Reading head first opens a window in which an event published to the hot, no-replay CDC
     * bus reaches neither backfill (capped at head) nor a late bus subscriber — lost for good. This pins
     * the read-after-attach ordering.
     */
    @Test
    void head_snapshot_is_taken_only_after_live_source_is_subscribed() {
        AtomicBoolean liveSubscribed              = new AtomicBoolean(false);
        AtomicBoolean headReadBeforeLiveSubscribe = new AtomicBoolean(false);
        AtomicInteger headReads                   = new AtomicInteger(0);

        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(2), pe(3));
        // never-completing live so the only terminal signal is take(3) on the backfill events
        Flux<PersistedEvent> live = Flux.<PersistedEvent>never()
                                        .doOnSubscribe(s -> liveSubscribed.set(true));

        LongSupplier headSnapshot = () -> {
            headReads.incrementAndGet();
            if (!liveSubscribed.get()) {
                headReadBeforeLiveSubscribe.set(true);
            }
            return 3L;
        };

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(
                backfill, live, headSnapshot, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered.take(3))
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 1L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 2L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 3L)
                    .verifyComplete();

        assertThat(headReadBeforeLiveSubscribe).as("head must be read only after live attach").isFalse();
        assertThat(headReads.get()).as("head snapshot is taken exactly once").isEqualTo(1);
    }

    /**
     * A global order that never reaches the bus - most commonly an {@code IDENTITY} value a rolled-back transaction took,
     * which writes no WAL - must not hold back the live events after it. The drain used to advance strictly by one past
     * the head, so it parked on such a hole until {@code eventBus.liveDrainStallThreshold} (three minutes by default)
     * re-subscribed the subscription through its backfill: every rolled-back append stalled it that long. The threshold
     * no longer plays a part, whatever it is set to - zero included, which used to park the drain for good.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 200, 180_000})
    void a_hole_in_the_live_tail_does_not_hold_back_the_live_events_after_it(long liveDrainStallThresholdMs) {
        var props = new CdcProperties.CdcEventBusProperties();
        props.setLiveDrainStallThreshold(Duration.ofMillis(liveDrainStallThresholdMs));

        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(2), pe(3));

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(
                backfill, liveSink.asFlux(), 3, props);

        StepVerifier.create(ordered.map(e -> e.globalEventOrder().longValue()).take(5))
                    .expectNext(1L, 2L, 3L)
                    .then(() -> liveSink.tryEmitNext(pe(5)))   // hole at 4 - never delivered
                    .expectNext(5L)
                    .then(() -> liveSink.tryEmitNext(pe(6)))
                    .expectNext(6L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(2));
    }

    /**
     * A transaction that took global order 4 commits after the one that took 5: the bus delivers 5 first. 5 is handed on
     * at once rather than held for 4, and 4 is delivered when it arrives - late and out of global order, once - as on
     * every other CDC and polling path. Handed over again, neither is delivered twice.
     */
    @Test
    void a_lower_global_order_committing_after_a_higher_one_in_the_live_tail_is_delivered_once_when_it_arrives() {
        var tracker = CdcDeliveryTracker.startingAfter("test", 0);
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(Flux.just(pe(1), pe(2), pe(3)),
                                                                                                  liveSink.asFlux(),
                                                                                                  () -> 3L,
                                                                                                  new CdcProperties.CdcEventBusProperties(),
                                                                                                  CdcEventStore.DeliveryRecorder.tracking(tracker));

        StepVerifier.create(ordered.map(e -> e.globalEventOrder().longValue()).take(6))
                    .expectNext(1L, 2L, 3L)
                    .then(() -> liveSink.tryEmitNext(pe(5)))   // 4 is still in flight
                    .expectNext(5L)
                    .then(() -> {
                        liveSink.tryEmitNext(pe(4));            // ... and commits
                        liveSink.tryEmitNext(pe(5));
                        liveSink.tryEmitNext(pe(4));
                    })
                    .expectNext(4L)
                    .then(() -> liveSink.tryEmitNext(pe(6)))
                    .expectNext(6L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(2));
        assertThat(tracker.watermark()).isEqualTo(6);
    }

    /**
     * Live events that arrived while the backfill ran are held and handed on in global order once it is done, and only
     * after every back-filled event - also when the subscriber takes one event at a time, so the back-filled events are
     * still queued downstream when the live ones are released.
     */
    @Test
    void every_back_filled_event_is_delivered_before_the_live_events_also_to_a_subscriber_taking_one_event_at_a_time() {
        int totalBackfill = 100;
        int totalLive     = 20;
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();
        // Out of order, before the backfill is done
        for (long globalOrder = totalBackfill + totalLive; globalOrder > totalBackfill; globalOrder--) {
            liveSink.tryEmitNext(pe(globalOrder));
        }
        Flux<PersistedEvent> backfill = Flux.range(1, totalBackfill)
                                            .map(globalOrder -> pe(globalOrder))
                                            .subscribeOn(Schedulers.newSingle("test-backfill", true));

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill,
                                                                                                  liveSink.asFlux().publishOn(Schedulers.newSingle("test-live", true)),
                                                                                                  totalBackfill,
                                                                                                  new CdcProperties.CdcEventBusProperties());

        var received = new CopyOnWriteArrayList<Long>();
        var subscriber = new BaseSubscriber<PersistedEvent>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(PersistedEvent event) {
                received.add(event.globalEventOrder().longValue());
                LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                request(1);
            }
        };
        ordered.subscribe(subscriber);

        await().atMost(Duration.ofSeconds(10)).until(() -> received.size() >= totalBackfill + totalLive);
        assertThat(received).containsExactlyElementsOf(LongStream.rangeClosed(1, totalBackfill + totalLive).boxed().toList());
        subscriber.dispose();
    }

    /**
     * A transaction that took global order 2 commits after the one that took 3: the backfill (head 3) cannot see 2, and
     * the bus delivers it once it commits - after the drain has moved past the head. It used to be dropped there as
     * "already back-filled"; it is delivered, late and out of global order, and the drain carries on.
     */
    @Test
    void a_live_event_at_or_below_the_head_the_backfill_could_not_see_is_delivered_once_the_backfill_is_done() {
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<PersistedEvent> backfill = Flux.just(pe(1), pe(3));

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfill, liveSink.asFlux(), 3, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered.map(e -> e.globalEventOrder().longValue()).take(4))
                    .expectNext(1L, 3L)
                    .then(() -> liveSink.tryEmitNext(pe(2)))
                    .expectNext(2L)
                    .then(() -> liveSink.tryEmitNext(pe(4)))
                    .expectNext(4L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
    }

    /**
     * The live source hands over an event at or below the head while the backfill still runs - it committed between the
     * attach and the head read, so the backfill loads it too. Held until the backfill is done, and delivered once.
     */
    @Test
    void a_live_event_the_backfill_also_loads_is_delivered_once_in_order() {
        Sinks.Many<PersistedEvent> backfillSink = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<PersistedEvent> liveSink     = Sinks.many().unicast().onBackpressureBuffer();

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(backfillSink.asFlux(), liveSink.asFlux(), 3, new CdcProperties.CdcEventBusProperties());

        StepVerifier.create(ordered.map(e -> e.globalEventOrder().longValue()).take(4))
                    .then(() -> {
                        liveSink.tryEmitNext(pe(3));
                        liveSink.tryEmitNext(pe(4));
                        backfillSink.tryEmitNext(pe(1));
                        backfillSink.tryEmitNext(pe(2));
                        backfillSink.tryEmitNext(pe(3));
                        backfillSink.tryEmitComplete();
                    })
                    .expectNext(1L, 2L, 3L, 4L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
    }

    /**
     * Everything handed downstream is recorded, so a live source that hands an event over again after it was delivered
     * - a catch-up re-reading from the watermark - cannot make it a duplicate
     */
    @Test
    void an_event_handed_over_again_after_it_was_delivered_is_dropped() {
        var tracker = CdcDeliveryTracker.startingAfter("test", 0);
        Sinks.Many<PersistedEvent> liveSink = Sinks.many().unicast().onBackpressureBuffer();

        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.orderedWithoutMetrics(Flux.just(pe(1), pe(2)),
                                                                                                  liveSink.asFlux(),
                                                                                                  () -> 2L,
                                                                                                  new CdcProperties.CdcEventBusProperties(),
                                                                                                  CdcEventStore.DeliveryRecorder.tracking(tracker));

        StepVerifier.create(ordered.map(e -> e.globalEventOrder().longValue()).take(4))
                    .expectNext(1L, 2L)
                    .then(() -> {
                        liveSink.tryEmitNext(pe(3));
                        liveSink.tryEmitNext(pe(2));
                        liveSink.tryEmitNext(pe(3));
                        liveSink.tryEmitNext(pe(1));
                        liveSink.tryEmitNext(pe(4));
                        liveSink.tryEmitNext(pe(5));
                    })
                    .expectNext(3L, 4L)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        assertThat(tracker.watermark()).isGreaterThanOrEqualTo(4);
    }

    private static PersistedEvent pe(long globalOrder) {
        return PersistedEvent.from(
                EventId.random(),
                ORDERS,
                OrderId.of("beed77fb-1115-1115-9c48-03ed5bfe8f89"),
                new EventJSON(EssentialsJSONEventSerializers.create(), EventType.of("TestEvent"), """
                                                                                                             {"type":"TestEvent","globalOrder":%d}
                                                                                                             """.formatted(globalOrder)),
                EventOrder.of(1L),
                EventRevision.of(1),
                GlobalEventOrder.of(globalOrder),
                new EventMetaDataJSON(EssentialsJSONEventSerializers.create(), "", ""),
                OffsetDateTime.now(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
                                  );
    }

}
