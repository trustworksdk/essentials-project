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

package dk.trustworks.essentials.components.eventsourced.aggregates.api;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregateLifecycleStatisticsApiTest {
    @Test
    void it_aggregates_snapshot_and_closing_books_metrics() {
        var snapshotRegistry = new InMemoryAggregateSnapshotPolicyRegistry();
        snapshotRegistry.register(new AggregateSnapshotPolicyDescriptor(TestAggregate.class,
                                                                       Optional.of("Orders"),
                                                                       TestAggregate.class.getAnnotation(AggregateSnapshotPolicy.class)));
        var closingBooksRegistry = new InMemoryAggregateClosingBooksPolicyRegistry();
        closingBooksRegistry.register(new AggregateClosingBooksPolicyDescriptor(TestAggregate.class,
                                                                               Optional.of("Orders"),
                                                                               TestAggregate.class.getAnnotation(dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy.class)));

        var meterRegistry = new SimpleMeterRegistry();
        meterRegistry.timer("essentials.aggregate_snapshot.load_snapshot", "aggregate_type", "Orders", "aggregate_impl_type", TestAggregate.class.getName())
                     .record(Duration.ofMillis(5));
        meterRegistry.counter("essentials.aggregate_closing_books.manager.poll.outcome", "aggregate_type", "Orders", "outcome", "success")
                     .increment();
        meterRegistry.counter("essentials.closing_books.time_boundary_gap_detected", "aggregate_type", "Orders", "policy_type", "TIME_BOUNDARY")
                     .increment(2);

        var api = new DefaultAggregateLifecycleStatisticsApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                                             snapshotRegistry,
                                                             closingBooksRegistry,
                                                             Optional.of(meterRegistry));

        assertThat(api.findAggregateSnapshotStatistics("principal"))
                .singleElement()
                .satisfies(stats -> assertThat(stats.timedMetrics()).containsKey("load_snapshot"));

        assertThat(api.findAggregateClosingBooksStatistics("principal"))
                .singleElement()
                .satisfies(stats -> {
                    assertThat(stats.counters()).containsEntry("essentials.aggregate_closing_books.manager.poll.outcome[outcome=success]", 1L);
                    assertThat(stats.counters()).containsEntry("essentials.closing_books.time_boundary_gap_detected[policy_type=TIME_BOUNDARY]", 2L);
                });
    }

    @Test
    void closing_books_counters_that_differ_only_by_meter_name_are_reported_separately() {
        // generations_closed and generations_opened carry no tags beyond aggregate_type, so keying them by
        // tags alone would collapse both into a single "count" entry and silently sum them.
        var closingBooksRegistry = new InMemoryAggregateClosingBooksPolicyRegistry();
        closingBooksRegistry.register(new AggregateClosingBooksPolicyDescriptor(TestAggregate.class,
                                                                               Optional.of("Orders"),
                                                                               TestAggregate.class.getAnnotation(dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy.class)));

        var meterRegistry = new SimpleMeterRegistry();
        meterRegistry.counter("essentials.aggregate_closing_books.generations_closed", "aggregate_type", "Orders").increment(3);
        meterRegistry.counter("essentials.aggregate_closing_books.generations_opened", "aggregate_type", "Orders").increment(4);
        meterRegistry.timer("essentials.aggregate_closing_books.rollover", "aggregate_type", "Orders").record(Duration.ofMillis(7));

        var api = new DefaultAggregateLifecycleStatisticsApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                                             new InMemoryAggregateSnapshotPolicyRegistry(),
                                                             closingBooksRegistry,
                                                             Optional.of(meterRegistry));

        assertThat(api.findAggregateClosingBooksStatistics("principal"))
                .singleElement()
                .satisfies(stats -> {
                    assertThat(stats.counters()).containsEntry("essentials.aggregate_closing_books.generations_closed", 3L);
                    assertThat(stats.counters()).containsEntry("essentials.aggregate_closing_books.generations_opened", 4L);
                    assertThat(stats.timedMetrics()).containsKey("rollover");
                });
    }

    @AggregateSnapshotPolicy(aggregateType = "Orders")
    @dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy(aggregateType = "Orders")
    private static class TestAggregate {
    }
}
