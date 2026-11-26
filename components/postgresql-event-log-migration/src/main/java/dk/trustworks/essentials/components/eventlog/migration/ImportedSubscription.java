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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The operator's mapping of one old durable subscription to a subscription of the event log. The
 * old key {@code (oldSubscriberId, definition.aggregateType())} names a row of the old engine's
 * {@code durable_subscriptions}; the definition and the progress model supply what that row does not have: the
 * ownership, the consistency mode, the filter and the handler kind. One old subscriber may be mapped to more than one
 * new subscription; each then starts from the same imported resume point.
 *
 * @param oldSubscriberId the old engine's {@code SubscriberId} value
 * @param definition      the new subscription; its aggregate type is the old row's aggregate type, which must be a type
 *                        of the migration plan
 * @param model           the progress model of the handler the subscription will run with: {@link ProgressModel#PERIODIC}
 *                        for a {@link PeriodicHandler}, {@link ProgressModel#IN_HANDLER_TRANSACTION} for a {@link PageHandler}
 * @param instanceNames   the progress rows to write: {@code [""]} for an exclusive definition; for a per-instance
 *                        definition the non-blank name of every instance that will run it (the new engine keeps one row
 *                        per instance), including the definition's own. Every row gets the same imported point
 * @param trustOldResumePoint the operator's statement that the old subscriber has already handled the events that filled
 *                        its open transient gaps. When set, the import writes the old resume
 *                        point {@code R} unchanged instead of the I8-safe {@code least(R, lowest filled open gap)}: the
 *                        subscription does not replay {@code [I, R)}, and an event that filled a gap and was in fact never
 *                        delivered is never delivered. The import report names the filled gaps this skips. Off by default
 *                        ({@link #exclusive}, {@link #perInstance}); set with {@link #trustingOldResumePoint()}
 */
public record ImportedSubscription(String oldSubscriberId, SubscriptionDefinition definition, ProgressModel model, List<String> instanceNames,
                                   boolean trustOldResumePoint) {
    public ImportedSubscription {
        requireNonBlank(oldSubscriberId, "No oldSubscriberId provided");
        requireNonNull(definition, "No definition provided");
        requireNonNull(model, "No model provided");
        requireNonNull(instanceNames, "No instanceNames provided");
        instanceNames = List.copyOf(instanceNames);
        if (definition.ownership() instanceof Ownership.Exclusive) {
            requireTrue(instanceNames.equals(List.of("")), "An exclusive subscription has one progress row, under the instance name \"\"; use ImportedSubscription.exclusive(...)");
        } else {
            requireNonEmpty(instanceNames, "A per-instance subscription needs the name of every instance that will run it");
            requireTrue(instanceNames.stream().noneMatch(String::isBlank), "Instance names must be non-blank");
            requireTrue(new HashSet<>(instanceNames).size() == instanceNames.size(), "Instance names must be distinct: " + instanceNames);
            requireTrue(instanceNames.contains(definition.ownership().instanceName()),
                        "The instance names " + instanceNames + " must include the definition's own instance '" + definition.ownership().instanceName() + "'");
        }
    }

    /**
     * @return the mapping of an old subscriber to an exclusive subscription
     */
    public static ImportedSubscription exclusive(String oldSubscriberId, SubscriptionDefinition definition, ProgressModel model) {
        return new ImportedSubscription(oldSubscriberId, definition, model, List.of(""), false);
    }

    /**
     * @param instanceNames every instance that will run the per-instance {@code definition}
     * @return the mapping of an old subscriber to a per-instance subscription
     */
    public static ImportedSubscription perInstance(String oldSubscriberId, SubscriptionDefinition definition, ProgressModel model, List<String> instanceNames) {
        return new ImportedSubscription(oldSubscriberId, definition, model, instanceNames, false);
    }

    /**
     * @return this mapping, opted in to importing the old resume point {@code R} unchanged (see {@code trustOldResumePoint}):
     * the operator states the old subscriber already handled the events that filled its open transient gaps
     */
    public ImportedSubscription trustingOldResumePoint() {
        return new ImportedSubscription(oldSubscriberId, definition, model, instanceNames, true);
    }

    /** @return the aggregate type, the old row's and the new subscription's */
    public String aggregateType() {
        return definition.aggregateType();
    }
}
