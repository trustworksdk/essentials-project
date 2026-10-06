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

package dk.trustworks.essentials.components.eventsourced.aggregates;

import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.types.EventId;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * <b>Framework internal.</b> Remembers the {@link CausationContext} cause that was bound when a resource - an aggregate,
 * or the events it produced - was registered with a {@link UnitOfWork}, so a repository that appends lazily in
 * {@link UnitOfWorkLifecycleCallback#beforeCommit(UnitOfWork, List)} can append under that cause rather than under
 * whatever happens to be bound when the commit runs.
 * <p>
 * The two can differ, and when they do the commit-time binding is the wrong one: an in-transaction event handler runs
 * inside the <em>appending</em> {@link UnitOfWork}'s commit, so an aggregate it changes is appended in a later pass of
 * that commit - after the handler's own binding has ended, under the cause of the work that appended the event the
 * handler was given. See {@code docs/event-causation.md}, phase 4.
 * <p>
 * Rules:
 * <ul>
 *     <li><b>The first registration wins.</b> An aggregate loaded under one cause and saved again under another in the same
 *     {@link UnitOfWork} keeps the first.</li>
 *     <li><b>"No cause" is captured too.</b> A resource registered with nothing bound is appended with an explicit "no
 *     cause" binding, so it never inherits an unrelated outer cause at commit.</li>
 *     <li><b>A resource that was never captured</b> is appended under whatever is bound at commit, i.e. as before.</li>
 * </ul>
 * Resources are matched by identity. Entries are dropped by {@link #release(UnitOfWork)}, which the owning callback calls
 * from {@code afterCommit} and {@code afterRollback}; the map is weak on the {@link UnitOfWork} as well, so a
 * {@link UnitOfWork} that is abandoned - or a read-only Spring transaction, which skips {@code afterCommit} - cannot
 * leak its entries.
 *
 * @param <RESOURCE> the type of resource registered with the {@link UnitOfWork}
 */
public final class CausesCapturedAtRegistration<RESOURCE> {
    private final Map<UnitOfWork, Map<RESOURCE, Optional<EventId>>> causesByUnitOfWork = new WeakHashMap<>();

    /**
     * Capture the cause currently bound for the resource, unless one was already captured for it in this {@link UnitOfWork}
     *
     * @param unitOfWork the {@link UnitOfWork} the resource is being registered with
     * @param resource   the resource
     */
    public void capture(UnitOfWork unitOfWork, RESOURCE resource) {
        requireNonNull(unitOfWork, "No unitOfWork provided");
        requireNonNull(resource, "No resource provided");
        var cause = CausationContext.current();
        synchronized (causesByUnitOfWork) {
            causesByUnitOfWork.computeIfAbsent(unitOfWork, ignored -> new IdentityHashMap<>())
                              .putIfAbsent(resource, cause);
        }
    }

    /**
     * Run the action under the cause captured for the resource, or - if none was captured - under whatever is bound now
     *
     * @param unitOfWork the {@link UnitOfWork} being committed
     * @param resource   the resource whose events the action appends
     * @param action     the action
     */
    public void runWithCapturedCause(UnitOfWork unitOfWork, RESOURCE resource, Runnable action) {
        requireNonNull(unitOfWork, "No unitOfWork provided");
        requireNonNull(resource, "No resource provided");
        requireNonNull(action, "No action provided");
        Optional<EventId> captured;
        synchronized (causesByUnitOfWork) {
            var causes = causesByUnitOfWork.get(unitOfWork);
            captured = causes != null ? causes.get(resource) : null;
        }
        if (captured == null) {
            action.run();
        } else {
            CausationContext.where(captured).run(action);
        }
    }

    /**
     * Forget everything captured for the {@link UnitOfWork}
     *
     * @param unitOfWork the {@link UnitOfWork} that has committed or rolled back
     */
    public void release(UnitOfWork unitOfWork) {
        requireNonNull(unitOfWork, "No unitOfWork provided");
        synchronized (causesByUnitOfWork) {
            causesByUnitOfWork.remove(unitOfWork);
        }
    }
}
