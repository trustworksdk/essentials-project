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
package dk.trustworks.essentials.components.eventlog;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * A read of one aggregate type's events by global order (phase 8 §5.2, the {@code EventStore} adapter's
 * {@code loadEventsByGlobalOrder}): the inclusive range {@code [fromInclusive, toInclusive]}, plus any
 * {@code additionalGlobalOrders} outside it, optionally narrowed to some event types and a tenant. Read with
 * {@link EventLog#loadByGlobalOrder(java.sql.Connection, GlobalOrderRead)} or {@link EventLog#loadByGlobalOrder(GlobalOrderRead)}.
 * <p>
 * Not a subscription: it returns the rows committed when the statement runs and makes no hole guarantee, so a
 * transaction that commits a lower order later is not seen.
 *
 * @param aggregateType          the aggregate type
 * @param fromInclusive          the lowest global order of the range
 * @param toInclusive            the highest global order of the range; {@link Long#MAX_VALUE} for an open range. A range
 *                               with {@code toInclusive < fromInclusive} is empty
 * @param additionalGlobalOrders orders read as well, typically outside the range; an order inside it is read once
 * @param eventTypes             the event type names to include; empty for every type
 * @param tenant                 {@code null} for every tenant; otherwise the events of that tenant <b>and the events
 *                               without a tenant</b>, as {@code PostgresqlEventStore} reads them (the subscription
 *                               tenant filter, by contrast, matches the tenant exactly)
 * @param fetchSize              rows per round trip; effective only on a connection with autocommit off
 */
public record GlobalOrderRead(String aggregateType, long fromInclusive, long toInclusive, List<Long> additionalGlobalOrders, Set<String> eventTypes,
                              String tenant, int fetchSize) {
    /**
     * The default {@link #fetchSize()}, the old event store's {@code DEFAULT_QUERY_BATCH_SIZE}
     */
    public static final int DEFAULT_FETCH_SIZE = 100;

    public GlobalOrderRead {
        requireNonBlank(aggregateType, "No aggregateType provided");
        requireNonNull(additionalGlobalOrders, "No additionalGlobalOrders provided");
        requireNonNull(eventTypes, "No eventTypes provided");
        additionalGlobalOrders.forEach(order -> requireNonNull(order, "An additional global order is null"));
        eventTypes.forEach(name -> requireTrue(name != null && !name.isBlank(), "An event type name is blank"));
        requireTrue(fetchSize > 0, "fetchSize must be positive");
        additionalGlobalOrders = List.copyOf(additionalGlobalOrders);
        eventTypes = Set.copyOf(eventTypes);
    }

    /**
     * @return every event of {@code aggregateType} in {@code [fromInclusive, toInclusive]}
     */
    public static GlobalOrderRead range(String aggregateType, long fromInclusive, long toInclusive) {
        return new GlobalOrderRead(aggregateType, fromInclusive, toInclusive, List.of(), Set.of(), null, DEFAULT_FETCH_SIZE);
    }

    /**
     * @return every event of {@code aggregateType} from {@code fromInclusive} on
     */
    public static GlobalOrderRead from(String aggregateType, long fromInclusive) {
        return range(aggregateType, fromInclusive, Long.MAX_VALUE);
    }

    public GlobalOrderRead withAdditionalGlobalOrders(Collection<Long> additionalGlobalOrders) {
        return new GlobalOrderRead(aggregateType, fromInclusive, toInclusive, List.copyOf(requireNonNull(additionalGlobalOrders, "No additionalGlobalOrders provided")),
                                   eventTypes, tenant, fetchSize);
    }

    public GlobalOrderRead withEventTypes(Set<String> eventTypes) {
        return new GlobalOrderRead(aggregateType, fromInclusive, toInclusive, additionalGlobalOrders, eventTypes, tenant, fetchSize);
    }

    /**
     * @param tenant {@code null} for every tenant
     */
    public GlobalOrderRead withTenant(String tenant) {
        return new GlobalOrderRead(aggregateType, fromInclusive, toInclusive, additionalGlobalOrders, eventTypes, tenant, fetchSize);
    }

    public GlobalOrderRead withFetchSize(int fetchSize) {
        return new GlobalOrderRead(aggregateType, fromInclusive, toInclusive, additionalGlobalOrders, eventTypes, tenant, fetchSize);
    }

    /**
     * @return whether the read can return nothing without a statement: an empty range and no additional orders
     */
    boolean isEmpty() {
        return toInclusive < fromInclusive && additionalGlobalOrders.isEmpty();
    }
}
