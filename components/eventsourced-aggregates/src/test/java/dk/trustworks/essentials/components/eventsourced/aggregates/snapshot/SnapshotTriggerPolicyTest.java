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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotTriggerPolicyTest {
    @Test
    void every_n_events_matches_existing_event_gap_behavior() {
        var policy = SnapshotTriggerPolicy.everyNEvents(3);

        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 1, Optional.empty()))).isFalse();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 2, Optional.empty()))).isTrue();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 3, Optional.empty()))).isTrue();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 5, Optional.of(EventOrder.of(2))))).isTrue();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 4, Optional.of(EventOrder.of(2))))).isFalse();
    }

    @Test
    void all_of_requires_all_policies_to_match() {
        var policy = SnapshotTriggerPolicy.allOf(SnapshotTriggerPolicy.everyNEvents(2),
                                                 SnapshotTriggerPolicy.minimumEventOrder(5),
                                                 SnapshotTriggerPolicy.onlyForAggregateTypes(AggregateType.of("Orders")));

        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 4, Optional.of(EventOrder.of(2))))).isFalse();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Payments"), 6, Optional.of(EventOrder.of(3))))).isFalse();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 6, Optional.of(EventOrder.of(3))))).isTrue();
    }

    @Test
    void any_of_matches_when_one_policy_matches() {
        var policy = SnapshotTriggerPolicy.anyOf(SnapshotTriggerPolicy.minimumEventOrder(10),
                                                 SnapshotTriggerPolicy.onlyForAggregateTypes(AggregateType.of("Orders")));

        assertThat(policy.shouldSchedule(context(AggregateType.of("Orders"), 1, Optional.empty()))).isTrue();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Payments"), 11, Optional.empty()))).isTrue();
        assertThat(policy.shouldSchedule(context(AggregateType.of("Payments"), 5, Optional.empty()))).isFalse();
    }

    @Test
    void snapshot_trigger_policy_is_usable_via_existing_strategy_contract() {
        AddNewAggregateSnapshotStrategy strategy = SnapshotTriggerPolicy.allOf(SnapshotTriggerPolicy.onlyForAggregateTypes(AggregateType.of("Orders")),
                                                                               SnapshotTriggerPolicy.everyNEvents(2));

        var shouldSchedule = strategy.shouldANewAggregateSnapshotBeAdded(new TestAggregate(),
                                                                         AddNewAggregateSnapshotStrategyTestData.persistedEvents(AggregateType.of("Orders"), EventOrder.of(1), EventOrder.of(2)),
                                                                         Optional.of(EventOrder.of(0)));

        assertThat(shouldSchedule).isTrue();
    }

    private SnapshotTriggerContext<String> context(AggregateType aggregateType,
                                                   long latestPersistedEventOrder,
                                                   Optional<EventOrder> latestSnapshotEventOrder) {
        return new SnapshotTriggerContext<>(aggregateType,
                                            "aggregate-1",
                                            TestAggregate.class,
                                            EventOrder.of(latestPersistedEventOrder),
                                            1,
                                            latestSnapshotEventOrder,
                                            OffsetDateTime.now());
    }

    private static final class TestAggregate {
    }
}
