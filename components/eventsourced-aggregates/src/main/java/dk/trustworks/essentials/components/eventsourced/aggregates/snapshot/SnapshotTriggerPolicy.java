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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.shared.collections.Lists;

import java.time.OffsetDateTime;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Defines the policy for triggering the scheduling of a snapshot.
 * A snapshot trigger policy evaluates whether a new snapshot should be created
 * based on the characteristics of the event stream or the aggregate type.
 * Multiple static factory methods are provided to create different snapshot
 * trigger policies.
 */
public interface SnapshotTriggerPolicy extends AddNewAggregateSnapshotStrategy {
    /**
     * Returns a {@link SnapshotTriggerPolicy} that triggers a snapshot after every fixed number of events.
     *
     * @param numberOfEvents the number of events after which a snapshot should be triggered; must be greater than or equal to 1
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots based on the specified number of events
     */
    static SnapshotTriggerPolicy everyNEvents(long numberOfEvents) {
        return new EveryNEventsSnapshotTriggerPolicy(numberOfEvents);
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that triggers a snapshot when the event order
     * reaches or exceeds the specified minimum threshold.
     *
     * @param minimumEventOrder the minimum event order after which a snapshot should be triggered;
     *                          must be greater than or equal to 0
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots based on the specified
     *         minimum event order
     */
    static SnapshotTriggerPolicy minimumEventOrder(long minimumEventOrder) {
        return new MinimumEventOrderSnapshotTriggerPolicy(minimumEventOrder);
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that triggers a snapshot only for the specified aggregate types.
     *
     * @param aggregateTypes the aggregate types for which snapshots should be triggered; must not be null
     * @return a {@link SnapshotTriggerPolicy} configured to trigger snapshots for the specified aggregate types
     */
    static SnapshotTriggerPolicy onlyForAggregateTypes(AggregateType... aggregateTypes) {
        return onlyForAggregateTypes(Arrays.asList(aggregateTypes));
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that triggers a snapshot only for the specified aggregate types.
     *
     * @param aggregateTypes the collection of aggregate types for which snapshots should be triggered; must not be null
     * @return a {@link SnapshotTriggerPolicy} configured to trigger snapshots for the specified aggregate types
     */
    static SnapshotTriggerPolicy onlyForAggregateTypes(Collection<AggregateType> aggregateTypes) {
        return new OnlyForAggregateTypesSnapshotTriggerPolicy(aggregateTypes);
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that combines multiple policies, requiring all
     * of the specified policies to pass their respective conditions for a snapshot to be triggered.
     *
     * @param policies the array of {@link SnapshotTriggerPolicy} instances that must all be satisfied
     *                 for a snapshot to be triggered; must not be null
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots only when all specified policies are satisfied
     */
    static SnapshotTriggerPolicy allOf(SnapshotTriggerPolicy... policies) {
        return allOf(Arrays.asList(policies));
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that combines multiple policies, requiring all of the
     * specified policies to pass their respective conditions for a snapshot to be triggered.
     *
     * @param policies the collection of {@link SnapshotTriggerPolicy} instances that must all be satisfied
     *                 for a snapshot to be triggered; must not be null or empty
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots only when all specified policies are satisfied
     */
    static SnapshotTriggerPolicy allOf(Collection<? extends SnapshotTriggerPolicy> policies) {
        return new AllOfSnapshotTriggerPolicy(policies);
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that combines multiple policies, triggering a snapshot
     * if any of the specified policies satisfies its condition.
     *
     * @param policies the array of {@link SnapshotTriggerPolicy} instances, at least one of which must be satisfied
     *                 to trigger a snapshot; must not be null
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots if any specified policy is satisfied
     */
    static SnapshotTriggerPolicy anyOf(SnapshotTriggerPolicy... policies) {
        return anyOf(Arrays.asList(policies));
    }

    /**
     * Returns a {@link SnapshotTriggerPolicy} that combines multiple policies, triggering a snapshot
     * if any of the specified policies satisfies its condition.
     *
     * @param policies the collection of {@link SnapshotTriggerPolicy} instances, at least one of which must be satisfied
     *                 to trigger a snapshot; must not be null or empty
     * @return a {@link SnapshotTriggerPolicy} that triggers snapshots if any specified policy is satisfied
     */
    static SnapshotTriggerPolicy anyOf(Collection<? extends SnapshotTriggerPolicy> policies) {
        return new AnyOfSnapshotTriggerPolicy(policies);
    }

    /**
     * Determines whether a snapshot should be scheduled based on the provided snapshot trigger context.
     * Evaluates the context and returns a boolean indicating if the criteria for triggering a snapshot
     * are met.
     *
     * @param context the snapshot trigger context containing information about the aggregate and
     *                its event stream; must not be null
     * @return {@code true} if a snapshot should be scheduled based on the provided context;
     *         {@code false} otherwise
     */
    boolean shouldSchedule(SnapshotTriggerContext<?> context);

    @Override
    default <ID, AGGREGATE_IMPL_TYPE> boolean shouldANewAggregateSnapshotBeAdded(AGGREGATE_IMPL_TYPE aggregate,
                                                                                 AggregateEventStream<ID> persistedEvents,
                                                                                 Optional<EventOrder> mostRecentlyStoredSnapshotLastIncludedEventOrder) {
        requireNonNull(aggregate, "No aggregate provided");
        requireNonNull(persistedEvents, "No persistedEvents provided");
        requireNonNull(mostRecentlyStoredSnapshotLastIncludedEventOrder, "No mostRecentlyStoredSnapshotLastIncludedEventOrder provided");

        return shouldSchedule(new SnapshotTriggerContext<>(persistedEvents.aggregateType(),
                                                           persistedEvents.aggregateId(),
                                                           aggregate.getClass(),
                                                           Lists.last(persistedEvents.eventList()).orElseThrow().eventOrder(),
                                                           persistedEvents.eventList().size(),
                                                           mostRecentlyStoredSnapshotLastIncludedEventOrder,
                                                           OffsetDateTime.now()));
    }

    final class EveryNEventsSnapshotTriggerPolicy implements SnapshotTriggerPolicy {
        private final long numberOfEvents;

        private EveryNEventsSnapshotTriggerPolicy(long numberOfEvents) {
            requireTrue(numberOfEvents >= 1, "numberOfEvents must be >= 1");
            this.numberOfEvents = numberOfEvents;
        }

        @Override
        public boolean shouldSchedule(SnapshotTriggerContext<?> context) {
            return context.latestPersistedEventOrder().longValue() - context.latestSnapshotEventOrder().map(EventOrder::longValue).orElse(-1L) >= numberOfEvents;
        }
    }

    final class MinimumEventOrderSnapshotTriggerPolicy implements SnapshotTriggerPolicy {
        private final long minimumEventOrder;

        private MinimumEventOrderSnapshotTriggerPolicy(long minimumEventOrder) {
            requireTrue(minimumEventOrder >= 0, "minimumEventOrder must be >= 0");
            this.minimumEventOrder = minimumEventOrder;
        }

        @Override
        public boolean shouldSchedule(SnapshotTriggerContext<?> context) {
            return context.latestPersistedEventOrder().longValue() >= minimumEventOrder;
        }
    }

    final class OnlyForAggregateTypesSnapshotTriggerPolicy implements SnapshotTriggerPolicy {
        private final Set<AggregateType> aggregateTypes;

        private OnlyForAggregateTypesSnapshotTriggerPolicy(Collection<AggregateType> aggregateTypes) {
            requireNonNull(aggregateTypes, "No aggregateTypes provided");
            requireFalse(aggregateTypes.isEmpty(), "aggregateTypes must not be empty");
            this.aggregateTypes = Set.copyOf(aggregateTypes);
        }

        @Override
        public boolean shouldSchedule(SnapshotTriggerContext<?> context) {
            return aggregateTypes.contains(context.aggregateType());
        }
    }

    final class AllOfSnapshotTriggerPolicy implements SnapshotTriggerPolicy {
        private final List<SnapshotTriggerPolicy> policies;

        private AllOfSnapshotTriggerPolicy(Collection<? extends SnapshotTriggerPolicy> policies) {
            requireNonNull(policies, "No policies provided");
            requireFalse(policies.isEmpty(), "policies must not be empty");
            this.policies = List.copyOf(policies);
        }

        @Override
        public boolean shouldSchedule(SnapshotTriggerContext<?> context) {
            return policies.stream().allMatch(policy -> policy.shouldSchedule(context));
        }
    }

    final class AnyOfSnapshotTriggerPolicy implements SnapshotTriggerPolicy {
        private final List<SnapshotTriggerPolicy> policies;

        private AnyOfSnapshotTriggerPolicy(Collection<? extends SnapshotTriggerPolicy> policies) {
            requireNonNull(policies, "No policies provided");
            requireFalse(policies.isEmpty(), "policies must not be empty");
            this.policies = List.copyOf(policies);
        }

        @Override
        public boolean shouldSchedule(SnapshotTriggerContext<?> context) {
            return policies.stream().anyMatch(policy -> policy.shouldSchedule(context));
        }
    }
}
