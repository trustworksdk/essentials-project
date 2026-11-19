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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link EventLogReactor}: the most specific {@code @MessageHandler} wins, the
 * {@link DeliveredEvent} second parameter, an unregistered aggregate type registered at start, two aggregate types, and a
 * handler failure parked by the engine - with a must-fail arm whose dispatcher swallows the failure.
 */
class EventLogReactorIT extends AbstractProcessorIT {
    private static final long   X        = 37;
    private static final String INVOICES = "Invoices";

    static class Notifier extends EventLogReactor {
        final Set<String> added   = ConcurrentHashMap.newKeySet();
        final Set<String> general = ConcurrentHashMap.newKeySet();
        long              failAt  = -1;

        Notifier(EventLogProcessorDependencies dependencies, List<String> aggregateTypes, Set<ProcessorMutant> mutants) {
            super("notifier", aggregateTypes, dependencies);
            seams(AbstractProcessorIT.seams(mutants, Set.of()));
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errors());
        }

        @MessageHandler
        void on(Added payload, DeliveredEvent event) {
            if (event.globalOrder().longValue() == failAt) {
                throw new ParkEventException("cannot notify");
            }
            added.add(event.aggregateType() + ":" + event.globalOrder());
        }

        @MessageHandler
        void on(OrderEvent payload) {
            general.add(payload.toString());
        }
    }

    private EventLogProcessorDependencies dependencies() {
        return new EventLogProcessorDependencies(eventLog, serializer, PageTransactionSource.engine());
    }

    @Test
    void the_most_specific_handler_gets_each_event() throws Exception {
        appendEvents(-1);
        var notifier = new Notifier(dependencies(), List.of(ORDERS), Set.of());
        notifier.start();
        try {
            awaitTrue("every event delivered", Duration.ofSeconds(20), () -> notifier.added.size() == EVENTS);
            assertThat(notifier.general).isEmpty();
            assertThat(notifier.reactors().getFirst().definition().consistency()).isEqualTo(ConsistencyMode.FRESH);
        } finally {
            notifier.stop();
        }
    }

    @Test
    void an_unregistered_aggregate_type_is_registered_at_start_beside_a_registered_one() throws Exception {
        var notifier = new Notifier(dependencies(), List.of(ORDERS, INVOICES), Set.of());
        notifier.start();
        try {
            appendOne(INVOICES, "i1", new Added("i1", 5));
            appendOne(ORDERS, "o1", new Added("o1", 6));
            awaitTrue("both aggregate types delivered", Duration.ofSeconds(20), () -> notifier.added.equals(Set.of(INVOICES + ":1", ORDERS + ":1")));
            assertThat(notifier.reactors()).extracting(reactor -> reactor.key().aggregateType()).containsExactly(ORDERS, INVOICES);
            awaitTrue("both running", Duration.ofSeconds(5), () -> notifier.reactors().stream().allMatch(EventReactor::isRunning));
        } finally {
            notifier.stop();
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

    /** {@link ParkEventException} thrown by the handler reaches the engine unwrapped: parked at once, no retries */
    private void failureArm(Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var notifier = new Notifier(dependencies(), List.of(ORDERS), mutants);
        notifier.failAt = X;
        notifier.start();
        try {
            var key = notifier.reactors().getFirst().key();
            awaitTrue("the failing event parked", Duration.ofSeconds(20), () -> parked(key).stream().anyMatch(row -> row.globalOrder() == X));
            var head = parked(key).stream().filter(row -> row.globalOrder() == X).findFirst().orElseThrow();
            assertThat(head.attempts()).isEqualTo(1);
            assertThat(notifier.added).doesNotContain(ORDERS + ":" + X);
        } finally {
            notifier.stop();
        }
    }
}
