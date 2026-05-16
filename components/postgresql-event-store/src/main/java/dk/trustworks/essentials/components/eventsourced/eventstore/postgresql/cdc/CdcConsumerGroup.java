/*
 *  Copyright 2021-2026 the original author or authors.
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

import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a consumer group for Change Data Capture (CDC) operations.
 * <p>
 * This class is used to encapsulate the name of a CDC consumer group and provides
 * methods to create and access the group name. Consumer groups are typically
 * utilized to organize and manage consumers that process change events.
 * <p>
 * Instances of this class are immutable.
 */
public final class CdcConsumerGroup {

    private final String name;

    private CdcConsumerGroup(String name) {
        requireNonNull(name, "No name provided");
        this.name = name;
    }

    public static CdcConsumerGroup of(String name) {
        return new CdcConsumerGroup(name);
    }

    public String name() {
        return name;
    }

    /**
     * Wrap a {@link SubscriberId} so that it carries this consumer group's name as a prefix
     * — recommended in multi-group deployments (see {@code cdc.md} §3.2) where two
     * deployments share the same PostgreSQL database. Without the prefix, two deployments
     * that happen to use the same {@code SubscriberId} for the same {@code AggregateType}
     * collide on the same {@code (subscriber_id, aggregate_type)} row in the framework's
     * {@code durable_subscriptions} table — they overwrite each other's resume points and
     * one consumer mysteriously rewinds.
     * <p>
     * Returns a new {@link SubscriberId} whose value is {@code "<group>.<originalId>"}. The
     * separator is a single dot to keep the resulting ID human-readable and grep-friendly in
     * logs (e.g. {@code orders.realtime-projector}, {@code billing.realtime-projector}).
     * <p>
     * Typical wiring in an application that uses Spring autoconfig:
     * <pre>
     * &#064;Autowired CdcConsumerGroup consumerGroup;
     *
     * subscriptionManager.subscribeToAggregateEventsAsynchronously(
     *     consumerGroup.namespaced(SubscriberId.of("realtime-projector")),
     *     ORDERS,
     *     startFrom,
     *     handler);
     * </pre>
     * Apps that run a single consumer group don't need to call this — the default
     * group's namespacing is still consistent within itself. The method exists to make
     * the right pattern obvious and ergonomic when you do scale to multiple groups.
     *
     * @param subscriberId the application's subscriber identifier (the part you'd write
     *                     without thinking about consumer groups)
     * @return a {@link SubscriberId} prefixed with this group's name
     */
    public SubscriberId namespaced(SubscriberId subscriberId) {
        requireNonNull(subscriberId, "No subscriberId provided");
        return SubscriberId.of(name + "." + subscriberId.value());
    }
}
