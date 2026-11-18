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
package dk.trustworks.essentials.components.eventlog.integration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.integration.test_data.*;
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
 * start, and a handler failure parked by the engine - with a must-fail arm whose dispatcher swallows the failure.
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

        TotalsView(List<String> aggregateTypes, Set<IntegrationMutant> mutants) {
            super("totals-view", aggregateTypes, new EventLogProcessorDependencies(eventLog, serializer, source(SourceKind.JDBI, Set.of())));
            seams(AbstractProcessorIT.seams(mutants, Set.of()));
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errors());
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
        assertThatThrownBy(() -> failureArm(Set.of(IntegrationMutant.ANNOTATED_DISPATCH_SWALLOWS_FAILURE))).isInstanceOf(AssertionError.class);
    }

    private void failureArm(Set<IntegrationMutant> mutants) throws Exception {
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
}
