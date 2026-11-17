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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

/**
 * Gate row I14 through {@link EventReactor} (design phase 8 §9.2 arm (f)): an event whose reaction always fails is parked
 * with its aggregate's later events behind it; every other event reaches its reaction at least once. Also arm (c) for the
 * periodic path, and dispatch on a payload's supertype.
 */
class EventReactorIT extends AbstractProcessorIT {
    private static final long X = 37;

    @Test
    void f_an_event_whose_reaction_always_fails_is_parked_and_the_rest_delivered() throws Exception {
        arm(Set.of(), -1);
    }

    @Test
    void must_fail_f_PROCESSOR_SWALLOWS_FAILURE() {
        assertThatThrownBy(() -> arm(Set.of(IntegrationMutant.PROCESSOR_SWALLOWS_FAILURE), -1)).isInstanceOf(AssertionError.class);
    }

    @Test
    void c_an_event_that_cannot_be_deserialized_is_parked() throws Exception {
        arm(Set.of(), X);
    }

    @Test
    void must_fail_c_DESERIALIZE_OUTSIDE_HANDLER_RETRY() {
        assertThatThrownBy(() -> arm(Set.of(IntegrationMutant.DESERIALIZE_OUTSIDE_HANDLER_RETRY), X)).isInstanceOf(AssertionError.class);
    }

    private void arm(Set<IntegrationMutant> mutants, long unknownAt) throws Exception {
        appendEvents(unknownAt);
        var received = ConcurrentHashMap.<Long>newKeySet();
        var reactor = EventReactor.builder("order-notifier", ORDERS)
                                  // dispatched on the payload's interface
                                  .on(OrderEvent.class, event -> {
                                      if (event.globalOrder().longValue() == X) {
                                          throw new IllegalStateException("always fails");
                                      }
                                      received.add(event.globalOrder().longValue());
                                  })
                                  .errors(errors())
                                  .seams(seams(mutants, Set.of()))
                                  .build(eventLog, serializer);
        reactor.start();
        try {
            var key  = reactor.key();
            var tail = tailOf(X);
            awaitTrue("the head parked and every other event delivered", Duration.ofSeconds(10),
                      () -> received.size() == EVENTS - tail.size() && parked(key).stream().anyMatch(row -> row.globalOrder() == X));
            var rows = parked(key);
            var head = rows.stream().filter(row -> row.globalOrder() == X).findFirst().orElseThrow();
            assertThat(head.attempts()).isEqualTo(errors().retryAttempts() + 1);
            awaitTrue("the held events parked behind the head", Duration.ofSeconds(10),
                      () -> parked(key).stream().map(ParkedRow::globalOrder).toList().containsAll(tail));
            assertThat(received).doesNotContainAnyElementsOf(tail);
            assertThat(reactor.definition().consistency()).isEqualTo(ConsistencyMode.FRESH);
            assertThat(reactor.definition().ownership()).isEqualTo(Ownership.exclusive());
        } finally {
            reactor.stop();
        }
    }
}
