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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.time.OffsetDateTime;
import java.util.Objects;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The durable position of a subscriber within an {@link AggregateType}'s event stream.
 * <p>
 * <b>Thread-safety:</b> instances are mutated from the thread processing events (which advances
 * {@link #advanceResumeFromAndIncluding(GlobalEventOrder)}) and read/marked-persisted from the
 * thread persisting them (the periodic snapshotter or {@code stop()}). All access to the mutable
 * state is therefore synchronized on the instance.
 * <p>
 * <b>Dirty tracking is value-based, not flag-based:</b> "persisted" is recorded as the
 * {@link GlobalEventOrder} that was actually written to the database rather than as a boolean.
 * A boolean flag loses updates - if the resume point advances while a save is in-flight, clearing
 * the flag on commit marks the newer, never-written value as clean, and since nothing re-dirties a
 * resume point that has stopped advancing, that progress is never persisted by any later save.
 */
public final class SubscriptionResumePoint {
    private final    SubscriberId     subscriberId;
    private final    AggregateType    aggregateType;
    private volatile GlobalEventOrder resumeFromAndIncluding;
    /** The value most recently confirmed written to the underlying store - {@link #isChanged()} is derived from it. */
    private volatile GlobalEventOrder lastPersistedResumeFromAndIncluding;
    private volatile OffsetDateTime   lastUpdated;

    public SubscriptionResumePoint(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder resumeFromAndIncluding, OffsetDateTime lastUpdated) {
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.resumeFromAndIncluding = requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        this.lastPersistedResumeFromAndIncluding = this.resumeFromAndIncluding;
        this.lastUpdated = requireNonNull(lastUpdated, "No lastUpdated provided");
    }

    public SubscriberId getSubscriberId() {
        return subscriberId;
    }

    public AggregateType getAggregateType() {
        return aggregateType;
    }

    public GlobalEventOrder getResumeFromAndIncluding() {
        return resumeFromAndIncluding;
    }

    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Unconditionally reposition the resume point - including <i>backwards</i>.<br>
     * Use this for deliberate repositioning (e.g. a subscription reset). To record consumption
     * progress use {@link #advanceResumeFromAndIncluding(GlobalEventOrder)} instead, which cannot
     * rewind.
     */
    public synchronized SubscriptionResumePoint setResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        this.resumeFromAndIncluding = resumeFromAndIncluding;
        return this;
    }

    /**
     * Move the resume point forward to {@code resumeFromAndIncluding}, ignoring the call when it
     * would move it backwards or leave it unchanged.<br>
     * <br>
     * Events are not necessarily <b>completed</b> in {@link GlobalEventOrder} order: when a transient
     * gap occurs (e.g. a database disruption) the {@code EventStreamGapHandler} re-delivers the
     * skipped events afterwards, so an older event can finish after newer ones have already been
     * handled. Assigning unconditionally would rewind the resume point to that straggler and cause
     * every event in between to be redelivered on the next resume. Advancing past a gap is safe
     * because unfilled gaps are tracked separately (and durably) by the {@code EventStreamGapHandler},
     * not by the resume point.
     */
    public synchronized SubscriptionResumePoint advanceResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        if (resumeFromAndIncluding.longValue() <= this.resumeFromAndIncluding.longValue()) {
            return this;
        }
        return setResumeFromAndIncluding(resumeFromAndIncluding);
    }

    /**
     * Mark the resume point as being in sync with the underlying store at its <i>current</i> value.
     *
     * @param lastUpdated the timestamp the value was written
     * @see #markAsPersisted(GlobalEventOrder, OffsetDateTime) to mark a specific value as written
     */
    public synchronized SubscriptionResumePoint setLastUpdated(OffsetDateTime lastUpdated) {
        return markAsPersisted(this.resumeFromAndIncluding, lastUpdated);
    }

    /**
     * Record that {@code persistedResumeFromAndIncluding} was successfully written to the underlying store.<br>
     * <br>
     * Callers must pass the value they actually wrote, <b>not</b> the current value: a resume point can
     * advance concurrently while the write is in-flight, and that newer value has <i>not</i> been persisted.
     * Passing it here would mark it clean and the progress would be silently lost, since the periodic
     * snapshotter only saves resume points that {@link #isChanged()}.
     *
     * @param persistedResumeFromAndIncluding the value that was written to the underlying store
     * @param lastUpdated                     the timestamp the value was written
     */
    public synchronized SubscriptionResumePoint markAsPersisted(GlobalEventOrder persistedResumeFromAndIncluding,
                                                                OffsetDateTime lastUpdated) {
        this.lastPersistedResumeFromAndIncluding = requireNonNull(persistedResumeFromAndIncluding, "No persistedResumeFromAndIncluding provided");
        this.lastUpdated = requireNonNull(lastUpdated, "No lastUpdated provided");
        return this;
    }

    /**
     * @return true if {@link #getResumeFromAndIncluding()} differs from the value last confirmed
     * written to the underlying store, i.e. this resume point needs saving
     */
    public boolean isChanged() {
        return !Objects.equals(resumeFromAndIncluding, lastPersistedResumeFromAndIncluding);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubscriptionResumePoint)) return false;
        SubscriptionResumePoint that = (SubscriptionResumePoint) o;
        return subscriberId.equals(that.subscriberId) && aggregateType.equals(that.aggregateType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subscriberId, aggregateType);
    }

    @Override
    public String toString() {
        return "SubscriptionResumePoint{" +
                "subscriberId=" + subscriberId +
                ", aggregateType=" + aggregateType +
                ", resumeFromAndIncluding=" + resumeFromAndIncluding +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}
