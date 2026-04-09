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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInClosingBooksPolicyEvaluatorTest {
    @Test
    void event_count_or_time_boundary_rolls_when_either_condition_is_met() {
        var evaluator = new BuiltInClosingBooksPolicyEvaluator<TestAggregate>(AggregateType.of("Accounts"),
                                                                              ClosingBooksDefaultPolicyType.EVENT_COUNT_OR_TIME_BOUNDARY,
                                                                              100,
                                                                              ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                              ZoneId.of("UTC"),
                                                                              null,
                                                                              Clock.fixed(Instant.parse("2026-05-02T00:00:00Z"), ZoneOffset.UTC),
                                                                              Optional.empty(),
                                                                              TestAggregate::eventCount,
                                                                              TestAggregate::periodId);

        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(5, "2026-04"))).isTrue();
        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(100, "2026-05"))).isTrue();
        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(5, "2026-05"))).isFalse();
    }

    @Test
    void records_metric_when_gap_is_detected() {
        var registry = new SimpleMeterRegistry();
        var evaluator = new BuiltInClosingBooksPolicyEvaluator<TestAggregate>(AggregateType.of("Accounts"),
                                                                              ClosingBooksDefaultPolicyType.TIME_BOUNDARY,
                                                                              100,
                                                                              ClosingBooksTimeBoundary.END_OF_MONTH,
                                                                              ZoneId.of("UTC"),
                                                                              null,
                                                                              Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC),
                                                                              Optional.of(registry),
                                                                              TestAggregate::eventCount,
                                                                              TestAggregate::periodId);

        assertThat(evaluator.shouldRolloverOnAccess(new TestAggregate(5, "2026-03"))).isTrue();
        assertThat(registry.get("essentials.closing_books.time_boundary_gap_detected")
                           .tag("aggregate_type", "Accounts")
                           .tag("time_boundary", "END_OF_MONTH")
                           .counter()
                           .count()).isEqualTo(1.0);
    }

    private record TestAggregate(long eventCount, String periodId) {
    }
}
