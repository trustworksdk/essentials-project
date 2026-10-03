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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;

import java.util.*;

/**
 * EventStoreApi serves as an interface to manage and query event store-related operations,
 * such as retrieving the highest persisted global event order or fetching all subscriptions.
 */
public interface EventStoreApi {

    /**
     * Retrieves the highest global event order that has been persisted in the event store
     * for the specified aggregate type. The result is encapsulated in an {@code Optional},
     * which will be empty if no events have been persisted for the given aggregate type.
     *
     * @param principal       the principal or identity querying the event store, typically
     *                        representing the authenticated user or system performing the action
     * @param aggregateType   the type of aggregate for which the highest persisted global event order
     *                        is being requested
     * @return an {@code Optional} containing the highest {@code GlobalEventOrder} if one exists
     *         for the specified aggregate type, or an empty {@code Optional} if no events exist
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<GlobalEventOrder> findHighestGlobalEventOrderPersisted(Object principal, AggregateType aggregateType);

    /**
     * Retrieves all subscriptions that are currently active in the system. Each subscription represents
     * a subscriber's subscription to an aggregate type, including details such as the subscriber ID,
     * the aggregate type, the current global order position, and the last updated timestamp.
     *
     * @param principal the principal or identity requesting the subscriptions, typically representing
     *                  the authenticated user or system performing the action
     * @return a list of {@code ApiSubscription} objects representing all active subscriptions in the system
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    List<ApiSubscription> findAllSubscriptions(Object principal);

    /**
     * Retrieves the runtime statistics collected for every subscription running in the queried instance, such as
     * event-handling throughput and timing, handler failures, polling activity and fenced-lock ownership.
     * <p>
     * Unlike {@link #findAllSubscriptions(Object)}, which is backed by the resume points shared by all instances
     * through the database, these statistics are collected in memory by the instance that runs the subscription. A
     * subscription running on another instance is therefore absent here, and an exclusive subscription only shows
     * event-handling activity on the instance that holds its fenced lock.
     *
     * @param principal the principal or identity requesting the statistics, typically representing
     *                  the authenticated user or system performing the action
     * @return a list of {@code ApiSubscriptionStatistics}, one per subscription observed in this instance. Empty if
     * statistics collection is disabled
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    List<ApiSubscriptionStatistics> findAllSubscriptionStatistics(Object principal);

    /**
     * Retrieves the runtime statistics collected for a single subscription running in the queried instance.
     *
     * @param principal     the principal or identity requesting the statistics, typically representing
     *                      the authenticated user or system performing the action
     * @param subscriberId  the id of the subscriber to return statistics for
     * @param aggregateType the aggregate type the subscriber subscribes to - a subscriber may subscribe to more than one
     * @return an {@code Optional} containing the statistics, or an empty {@code Optional} if the subscription is not
     * running in this instance or statistics collection is disabled
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiSubscriptionStatistics> findSubscriptionStatistics(Object principal,
                                                                  SubscriberId subscriberId,
                                                                  AggregateType aggregateType);

    /**
     * The largest {@code maxDepth} {@link #findCausationChain(Object, EventId, int)} accepts
     */
    int MAX_CAUSATION_CHAIN_DEPTH = 100;

    /**
     * Find an event by its id alone, in whichever registered aggregate type's event stream holds it. Describes the
     * event's identity and cause only - no payloads.
     *
     * @param principal the principal or identity making the request
     * @param eventId   the id of the event
     * @return the event, or {@link Optional#empty()} if no registered event stream holds it
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    Optional<ApiCausationEvent> findEvent(Object principal, EventId eventId);

    /**
     * "Why did this happen?" - the event and the chain of events that caused it, walking back one recorded cause at a
     * time: the event itself first, then its cause, then that event's cause, and so on.<br>
     * The walk stops at an event with no recorded cause, at a cause no registered event stream holds, after
     * {@code maxDepth} events, or if it would revisit an event.
     *
     * @param principal the principal or identity making the request
     * @param eventId   the id of the event to start from
     * @param maxDepth  the most events to return, 1 to {@link #MAX_CAUSATION_CHAIN_DEPTH}
     * @return the chain, starting with the event itself; empty if no registered event stream holds it
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     */
    List<ApiCausationEvent> findCausationChain(Object principal, EventId eventId, int maxDepth);

    /**
     * "What did this cause?" - every event whose recorded cause is the given event, across every registered aggregate
     * type: in aggregate-type table-name order, and global event order within an aggregate type. Direct effects only;
     * call again with an effect's id to walk further.
     *
     * @param principal the principal or identity making the request
     * @param eventId   the id of the causing event
     * @return the events it caused; empty if none
     * @throws dk.trustworks.essentials.shared.security.EssentialsSecurityException if the principal is not authorized to access
     * @throws dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.CausationIndexNotEnabledException
     *                                                                              if the caused-by-event-id index is not enabled
     */
    List<ApiCausationEvent> findEventsCausedBy(Object principal, EventId eventId);
}
