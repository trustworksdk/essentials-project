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

package dk.trustworks.essentials.components.eventlog.integration.api;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.sql.SQLException;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

/**
 * {@link EventLogSubscriptionsApi} over an {@link EventLog}'s {@link EventLogAdmin}
 */
public final class DefaultEventLogSubscriptionsApi implements EventLogSubscriptionsApi {
    /** The largest parked page served; a larger ask is clamped */
    public static final int MAX_PAGE_SIZE = 1_000;

    private final EssentialsSecurityProvider securityProvider;
    private final EventLog                   eventLog;

    public DefaultEventLogSubscriptionsApi(EssentialsSecurityProvider securityProvider, EventLog eventLog) {
        this.securityProvider = requireNonNull(securityProvider, "No securityProvider provided");
        this.eventLog = requireNonNull(eventLog, "No eventLog provided");
    }

    @Override
    public List<ApiEventLogSubscription> getSubscriptions(Object principal, Optional<String> aggregateType) {
        reader(principal);
        requireNonNull(aggregateType, "No aggregateType provided");
        return call("list subscriptions", () -> eventLog.admin().subscriptions(aggregateType).stream().map(ApiEventLogSubscription::from).toList());
    }

    @Override
    public Optional<ApiEventLogSubscription> getSubscription(Object principal, ProgressKey key) {
        reader(principal);
        requireNonNull(key, "No key provided");
        return call("read subscription " + key, () -> eventLog.admin().subscription(key).map(ApiEventLogSubscription::from));
    }

    @Override
    public ApiParkedEventsPage getParkedEvents(Object principal, ProgressKey key, long afterGlobalOrder, int limit) {
        reader(principal);
        requireNonNull(key, "No key provided");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return call("read the parked events of " + key, () -> ApiParkedEventsPage.from(eventLog.admin().parked(key, afterGlobalOrder, Math.min(limit, MAX_PAGE_SIZE))));
    }

    @Override
    public boolean resetSubscription(Object principal, ProgressKey key, long resumeFrom) {
        writer(principal);
        requireNonNull(key, "No key provided");
        if (resumeFrom < 1) {
            throw new IllegalArgumentException("resumeFrom must be >= 1");
        }
        return call("reset " + key, () -> eventLog.admin().reset(key, resumeFrom));
    }

    @Override
    public ParkedRequestResult retryParkedEvent(Object principal, ProgressKey key, long headGlobalOrder) {
        writer(principal);
        requireNonNull(key, "No key provided");
        return call("retry " + headGlobalOrder + " of " + key, () -> eventLog.admin().retryParked(key, headGlobalOrder));
    }

    @Override
    public ParkedRequestResult discardParkedEvent(Object principal, ProgressKey key, long headGlobalOrder) {
        writer(principal);
        requireNonNull(key, "No key provided");
        return call("discard " + headGlobalOrder + " of " + key, () -> eventLog.admin().discardParked(key, headGlobalOrder));
    }

    @Override
    public boolean deleteRetiredInstance(Object principal, ProgressKey key) {
        writer(principal);
        requireNonNull(key, "No key provided");
        return call("delete retired instance " + key, () -> eventLog.admin().deleteRetiredInstance(key));
    }

    private void reader(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }

    private void writer(Object principal) {
        validateHasAnyEssentialsSecurityRoles(securityProvider, principal, SUBSCRIPTION_WRITER, ESSENTIALS_ADMIN);
    }

    private interface Call<T> {
        T call() throws SQLException;
    }

    private static <T> T call(String what, Call<T> call) {
        try {
            return call.call();
        } catch (SQLException e) {
            throw new EventLogAdminException("Failed to " + what, e);
        }
    }
}
