/*
 *  Copyright 2021-2025 the original author or authors.
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
import reactor.core.publisher.*;
import reactor.test.StepVerifier;

import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.Wal2JsonWithEssentialsAggregateIT.ORDERS;
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
        Flux<PersistedEvent> ordered = CdcEventStore.BackfillThenLiveOrdered.ordered(backfill, live, 3);

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
                CdcEventStore.BackfillThenLiveOrdered.ordered(backfill, live, 3);

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
                CdcEventStore.BackfillThenLiveOrdered.ordered(backfill, live, 3);

        StepVerifier.create(ordered.take(4))
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 1L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 2L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 3L)
                    .expectNextMatches(e -> e.globalEventOrder().longValue() == 4L) // MUST NOT be missed
                    .verifyComplete();
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
