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
 * The admin SPI of the new event store engine's subscriptions (design phase 8 §4.4), one operation per
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

    /** @return {@code false} if there is no such subscription (none is created) */
    boolean resetSubscription(Object principal, ProgressKey key, long resumeFrom);

    ParkedRequestResult retryParkedEvent(Object principal, ProgressKey key, long headGlobalOrder);

    ParkedRequestResult discardParkedEvent(Object principal, ProgressKey key, long headGlobalOrder);

    /** Delete a retired per-instance subscription's row and parked rows */
    boolean deleteRetiredInstance(Object principal, ProgressKey key);
}
