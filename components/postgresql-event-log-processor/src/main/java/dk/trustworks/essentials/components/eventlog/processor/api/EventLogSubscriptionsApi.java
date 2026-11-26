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

package dk.trustworks.essentials.components.eventlog.processor.api;

import dk.trustworks.essentials.components.eventlog.*;

import java.util.*;

/**
 * The admin SPI of the new event store engine's subscriptions, one operation per
 * {@link EventLogAdmin} operation, keyed by {@link ProgressKey}. Works from the database on any node. Reads need
 * {@code SUBSCRIPTION_READER}, every change {@code SUBSCRIPTION_WRITER} ({@code ESSENTIALS_ADMIN} may do both).
 * Database failures surface as {@link EventLogAdminException}.
 */
public interface EventLogSubscriptionsApi {
    /** Every progress row of every node, optionally of one aggregate type */
    List<ApiEventLogSubscription> getSubscriptions(Object principal, Optional<String> aggregateType);

    Optional<ApiEventLogSubscription> getSubscription(Object principal, ProgressKey key);

    /** Parked rows with {@code global_order > afterGlobalOrder}, at most {@code limit} (capped at {@link DefaultEventLogSubscriptionsApi#MAX_PAGE_SIZE}) */
    ApiParkedEventsPage getParkedEvents(Object principal, ProgressKey key, long afterGlobalOrder, int limit);

    /**
     * Move a subscription's resume point. Nothing its handler already wrote is undone: a view's subscription would
     * re-apply events on top of the rows it holds, so a view is rebuilt instead ({@code ViewProjection.rebuild},
     * {@code EventLogViewProcessor.rebuild()}). The starter's implementation refuses the subscriptions of the views and
     * processors it knows.
     *
     * @return {@code false} if there is no such subscription (none is created)
     * @throws IllegalArgumentException if the subscription must not be reset directly, naming what to use instead
     */
    boolean resetSubscription(Object principal, ProgressKey key, long resumeFrom);

    ParkedRequestResult retryParkedEvent(Object principal, ProgressKey key, long headGlobalOrder);

    ParkedRequestResult discardParkedEvent(Object principal, ProgressKey key, long headGlobalOrder);

    /**
     * Retry every parked head of the subscription, terminal ones included: the request is stored on each head in one
     * statement and served by the instance running the subscription, a few heads per pass, as a single retry is. The
     * events held behind a head follow it. This call does not wait for the heads to be served.
     *
     * @return {@link ApiBulkParkedRequestResult#subscriptionFound()} {@code false} if there is no such subscription (nothing
     * is stored); otherwise how many heads the request was stored on
     */
    ApiBulkParkedRequestResult retryAllParked(Object principal, ProgressKey key);

    /** As {@link #retryAllParked}, but every parked head is deleted without being handled; the events held behind them are handled next */
    ApiBulkParkedRequestResult discardAllParked(Object principal, ProgressKey key);

    /** Delete a retired per-instance subscription's row and parked rows */
    boolean deleteRetiredInstance(Object principal, ProgressKey key);
}
