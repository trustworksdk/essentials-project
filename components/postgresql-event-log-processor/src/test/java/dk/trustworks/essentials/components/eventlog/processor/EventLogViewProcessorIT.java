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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link EventLogViewProcessor} (design phase 10 §3.5): {@code @MessageHandler} dispatch with the {@link DeliveredEvent}
 * second parameter, the page's unit of work in a handler, several aggregate types, an unregistered type registered at
 * start, a handler failure parked by the engine - with a must-fail arm whose dispatcher swallows the failure - and a
 * rebuild that clears the view in the transaction that resets the subscription, then replays it - with a must-fail arm
 * whose clear commits on its own.
 */
class EventLogViewProcessorIT extends AbstractProcessorIT {
    private static final long   X        = 37;
    private static final String INVOICES = "Invoices";

    /** A payload no handler takes */
    record Noted(String note) {
    }

    /** Writes the view through the page's unit of work, as an old ViewEventProcessor would */
    class TotalsView extends EventLogViewProcessor {
        final Set<String> seen = ConcurrentHashMap.newKeySet();
        long              failAt = -1;
        int               rebuilds;
        boolean           failRebuild;

        TotalsView(List<String> aggregateTypes, Set<ProcessorMutant> mutants) {
            super("totals-view", aggregateTypes, new EventLogProcessorDependencies(eventLog, serializer, source(SourceKind.JDBI, Set.of())));
            seams(AbstractProcessorIT.seams(mutants, Set.of()));
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errors());
        }

        /** Clears through the rebuild transaction's unit of work, as a handler writes */
        @Override
        protected void onRebuild(ProjectionContext context) {
            rebuilds++;
            assertThat(unitOfWorkFactory.getRequiredUnitOfWork().handle()).isSameAs(context.handle());
            context.handle().execute("TRUNCATE view_totals, applied");
            if (failRebuild) {
                throw new IllegalStateException("rebuild fails after the clear");
            }
        }

        @MessageHandler
        private void on(Added added, DeliveredEvent event) throws SQLException {
            if (event.globalOrder().longValue() == failAt) {
                throw new IllegalStateException("always fails");
            }
            seen.add(event.aggregateType() + ":" + event.globalOrder());
            if (event.aggregateType().toString().equals(ORDERS)) {
                applyToView(unitOfWorkFactory.getRequiredUnitOfWork().handle().getConnection(), event);
            }
        }
    }

    @Test
    void handlers_write_through_the_page_unit_of_work_and_unhandled_payloads_count_as_handled() throws Exception {
        appendEvents(-1);
        appendOne(ORDERS, "o0", new Noted("no handler"));
        var view = new TotalsView(List.of(ORDERS), Set.of());
        view.start();
        try {
            assertThat(view.isStarted()).isTrue();
            var key = view.projections().getFirst().key();
            awaitTrue("caught up past the unhandled event", Duration.ofSeconds(20), () -> resumePoint(key) > EVENTS + 1);
            assertThat(view()).isEqualTo(fold(Set.of()));
            assertThat(applied().values()).hasSize(EVENTS).containsOnly(1);
            assertThat(parked(key)).isEmpty();
            assertThat(view.projections().getFirst().definition().consistency()).isEqualTo(ConsistencyMode.CONTIGUOUS);
        } finally {
            view.stop();
        }
        assertThat(view.isStarted()).isFalse();
    }

    @Test
    void one_processor_follows_several_aggregate_types_and_registers_an_unregistered_one() throws Exception {
        appendEvents(-1);
        var view = new TotalsView(List.of(ORDERS, INVOICES), Set.of());
        view.start();
        try {
            assertThat(view.projections()).extracting(projection -> projection.key().aggregateType()).containsExactly(ORDERS, INVOICES);
            appendOne(INVOICES, "i1", new Added("i1", 5));
            awaitTrue("both aggregate types delivered", Duration.ofSeconds(20),
                      () -> view.seen.size() == EVENTS + 1 && view.seen.contains(INVOICES + ":1"));
        } finally {
            view.stop();
        }
    }

    @Test
    void a_handler_failure_is_parked_by_the_engine() throws Exception {
        failureArm(Set.of());
    }

    @Test
    void must_fail_ANNOTATED_DISPATCH_SWALLOWS_FAILURE() {
        assertThatThrownBy(() -> failureArm(Set.of(ProcessorMutant.ANNOTATED_DISPATCH_SWALLOWS_FAILURE))).isInstanceOf(AssertionError.class);
    }

    private void failureArm(Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var view = new TotalsView(List.of(ORDERS), mutants);
        view.failAt = X;
        view.start();
        try {
            var key = view.projections().getFirst().key();
            awaitTrue("the failing event parked", Duration.ofSeconds(20), () -> parked(key).stream().anyMatch(row -> row.globalOrder() == X));
            awaitTrue("caught up past the parked event", Duration.ofSeconds(20), () -> resumePoint(key) > EVENTS);
            assertThat(applied()).doesNotContainKey(X);
        } finally {
            view.stop();
        }
    }

    @Test
    void a_rebuild_clears_the_view_with_the_reset_and_replays_it() throws Exception {
        appendEvents(-1);
        var view = new TotalsView(List.of(ORDERS), Set.of());
        view.start();
        try {
            var key = view.projections().getFirst().key();
            awaitCaughtUp(key);
            assertThat(view()).isEqualTo(fold(Set.of()));

            assertThat(view.rebuild()).isTrue();

            assertThat(view.rebuilds).isEqualTo(1);
            assertThat(view.isStarted()).isTrue();
            awaitCaughtUp(key);
            awaitTrue("the view replayed", Duration.ofSeconds(20), () -> view().equals(fold(Set.of())));
            // Every event applied once since the clear: without it each would count twice, without the reset none would
            assertThat(applied().values()).hasSize(EVENTS).containsOnly(1);
        } finally {
            view.stop();
        }
    }

    @Test
    void a_failing_rebuild_rolls_the_clear_back_and_restarts_the_view() throws Exception {
        appendEvents(-1);
        var view = new TotalsView(List.of(ORDERS), Set.of());
        view.failRebuild = true;
        view.start();
        try {
            var key = view.projections().getFirst().key();
            awaitCaughtUp(key);

            assertThatThrownBy(view::rebuild).hasMessageContaining("rebuild fails after the clear");

            assertThat(view()).isEqualTo(fold(Set.of()));
            assertThat(resumePoint(key)).isGreaterThan(EVENTS);
            assertThat(view.isStarted()).isTrue();
            assertThat(view.projections().getFirst().isRunning()).isTrue();
        } finally {
            view.stop();
        }
    }

    @Test
    void a_rebuild_with_nothing_to_reset_leaves_the_view() throws Exception {
        nothingToResetArm(Set.of());
    }

    @Test
    void must_fail_REBUILD_CLEARS_OUTSIDE_RESET_TRANSACTION() {
        assertThatThrownBy(() -> nothingToResetArm(Set.of(ProcessorMutant.REBUILD_CLEARS_OUTSIDE_RESET_TRANSACTION))).isInstanceOf(AssertionError.class);
    }

    /** Never started, so no progress row: the reset finds nothing, and the clear must roll back with it */
    private void nothingToResetArm(Set<ProcessorMutant> mutants) throws Exception {
        execute("INSERT INTO view_totals VALUES ('kept', 1)");
        var view = new TotalsView(List.of(ORDERS), mutants);

        assertThat(view.rebuild()).isFalse();

        assertThat(view.rebuilds).isEqualTo(1);
        assertThat(view.isStarted()).isFalse();
        assertThat(view()).containsExactly(Map.entry("kept", 1L));
    }
}
