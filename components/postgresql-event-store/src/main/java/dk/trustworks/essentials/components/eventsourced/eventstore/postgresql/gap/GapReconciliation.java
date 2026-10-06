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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * What one {@link SubscriptionGapHandler#reconcileGapsAndReport reconciliation} of a query's result changed for one
 * subscriber.
 * <p>
 * A gap is a {@link GlobalEventOrder} the query range covered but no event was returned for - most often a
 * transaction that took the value and has not committed yet, or one that rolled back and never will. A new gap starts
 * out <i>transient</i>, and the subscriber keeps asking for it; it is <i>resolved</i> if its event turns up, or
 * <i>promoted to permanent</i> once the promotion strategy decides it never will, after which the subscriber stops
 * waiting for it.
 * <p>
 * The three counts answer the question an operator actually has: is this subscriber finding gaps, are they closing on
 * their own, and how many has it given up on. A steadily growing {@link #promotedToPermanentGaps()} is the one to
 * watch - each is a {@link GlobalEventOrder} this subscriber will never deliver an event for.
 *
 * @param newTransientGaps        gaps first registered by this reconciliation
 * @param resolvedTransientGaps   previously registered transient gaps whose event was returned by this query
 * @param promotedToPermanentGaps transient gaps this subscriber stopped waiting for, because they were promoted to
 *                                permanent gaps. Counted whether or not another subscriber had already recorded the
 *                                permanent gap, since for this subscriber the outcome is the same
 */
public record GapReconciliation(int newTransientGaps, int resolvedTransientGaps, int promotedToPermanentGaps) {

    /**
     * Nothing changed - also what a {@link SubscriptionGapHandler} that does not report reconciles to.
     */
    public static final GapReconciliation NONE = new GapReconciliation(0, 0, 0);

    public GapReconciliation {
        requireTrue(newTransientGaps >= 0, "newTransientGaps must not be negative");
        requireTrue(resolvedTransientGaps >= 0, "resolvedTransientGaps must not be negative");
        requireTrue(promotedToPermanentGaps >= 0, "promotedToPermanentGaps must not be negative");
    }

    /**
     * @return true if this reconciliation changed nothing
     */
    public boolean isEmpty() {
        return newTransientGaps == 0 && resolvedTransientGaps == 0 && promotedToPermanentGaps == 0;
    }

    /**
     * @param other another reconciliation of the same subscriber and aggregate type
     * @return what this and {@code other} changed together
     */
    public GapReconciliation plus(GapReconciliation other) {
        return new GapReconciliation(newTransientGaps + other.newTransientGaps,
                                     resolvedTransientGaps + other.resolvedTransientGaps,
                                     promotedToPermanentGaps + other.promotedToPermanentGaps);
    }
}
