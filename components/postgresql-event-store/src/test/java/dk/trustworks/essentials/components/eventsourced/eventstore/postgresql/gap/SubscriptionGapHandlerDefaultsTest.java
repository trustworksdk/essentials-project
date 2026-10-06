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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@link SubscriptionGapHandler#giveUpTransientGaps(AggregateType, List)} default: what a custom gap handler that does
 * not override it is asked to reconcile when a subscription gives up on its transient gaps
 */
class SubscriptionGapHandlerDefaultsTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    @Test
    void giving_up_reconciles_once_as_a_query_up_to_the_highest_gap_that_asked_for_the_gaps_and_found_no_events() {
        var handler = new RecordingGapHandler();

        var outcome = handler.giveUpTransientGaps(ORDERS, List.of(GlobalEventOrder.of(5), GlobalEventOrder.of(9)));

        assertThat(handler.reconciliations).containsExactly(
                new Reconciliation(ORDERS, LongRange.only(9), List.of(), List.of(GlobalEventOrder.of(5), GlobalEventOrder.of(9))));
        assertThat(outcome).isEqualTo(RecordingGapHandler.REPORTED);
    }

    @Test
    void giving_up_queries_up_to_the_highest_gap_whatever_order_the_gaps_are_given_in() {
        var handler = new RecordingGapHandler();

        handler.giveUpTransientGaps(ORDERS, List.of(GlobalEventOrder.of(9), GlobalEventOrder.of(5)));

        assertThat(handler.reconciliations).singleElement()
                                           .extracting(Reconciliation::globalOrderQueryRange)
                                           .isEqualTo(LongRange.only(9));
    }

    @Test
    void giving_up_on_no_gaps_reconciles_nothing() {
        var handler = new RecordingGapHandler();

        var outcome = handler.giveUpTransientGaps(ORDERS, List.of());

        assertThat(outcome).isSameAs(GapReconciliation.NONE);
        assertThat(handler.reconciliations).isEmpty();
    }

    /**
     * A gap handler that overrides only the per-order give-up keeps receiving every order of the ranges
     */
    @Test
    void giving_up_ranges_gives_up_every_order_in_them() {
        var handler = new RecordingGapHandler();

        var outcome = handler.giveUpTransientGapRanges(ORDERS, List.of(LongRange.between(5, 7), LongRange.only(9)));

        assertThat(handler.reconciliations).containsExactly(
                new Reconciliation(ORDERS, LongRange.only(9), List.of(), List.of(GlobalEventOrder.of(5), GlobalEventOrder.of(6), GlobalEventOrder.of(7), GlobalEventOrder.of(9))));
        assertThat(outcome).isEqualTo(RecordingGapHandler.REPORTED);
    }

    @Test
    void giving_up_no_ranges_reconciles_nothing() {
        var handler = new RecordingGapHandler();

        assertThat(handler.giveUpTransientGapRanges(ORDERS, List.of())).isSameAs(GapReconciliation.NONE);
        assertThat(handler.reconciliations).isEmpty();
    }

    @Test
    void adding_transient_gaps_records_nothing_by_default() {
        var handler = new RecordingGapHandler();

        assertThat(handler.addTransientGaps(ORDERS, LongRange.between(1, 5))).isSameAs(GapReconciliation.NONE);
        assertThat(handler.reconciliations).isEmpty();
    }

    private record Reconciliation(AggregateType aggregateType,
                                  LongRange globalOrderQueryRange,
                                  List<PersistedEvent> persistedEvents,
                                  List<GlobalEventOrder> transientGapsIncludedInQuery) {
    }

    /**
     * Records every {@link #reconcileGapsAndReport} call and overrides none of the defaults under test
     */
    private static final class RecordingGapHandler implements SubscriptionGapHandler {
        static final GapReconciliation REPORTED = new GapReconciliation(0, 0, 2);

        final List<Reconciliation> reconciliations = new ArrayList<>();

        @Override
        public GapReconciliation reconcileGapsAndReport(AggregateType aggregateType,
                                                        LongRange globalOrderQueryRange,
                                                        List<PersistedEvent> persistedEvents,
                                                        List<GlobalEventOrder> transientGapsIncludedInQuery) {
            reconciliations.add(new Reconciliation(aggregateType, globalOrderQueryRange, List.copyOf(persistedEvents), List.copyOf(transientGapsIncludedInQuery)));
            return REPORTED;
        }

        @Override
        public void reconcileGaps(AggregateType aggregateType,
                                  LongRange globalOrderQueryRange,
                                  List<PersistedEvent> persistedEvents,
                                  List<GlobalEventOrder> transientGapsIncludedInQuery) {
            throw new UnsupportedOperationException("The defaults under test reconcile through reconcileGapsAndReport");
        }

        @Override
        public SubscriberId subscriberId() {
            return SubscriberId.of("Recording");
        }

        @Override
        public List<GlobalEventOrder> findTransientGapsToIncludeInQuery(AggregateType aggregateType, LongRange globalOrderQueryRange) {
            return List.of();
        }

        @Override
        public List<GlobalEventOrder> resetTransientGapsFor(AggregateType aggregateType) {
            return List.of();
        }

        @Override
        public List<GlobalEventOrder> getTransientGapsFor(AggregateType aggregateType) {
            return List.of();
        }

        @Override
        public Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
            return Stream.empty();
        }
    }
}
