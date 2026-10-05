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

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The durable position of a subscriber within an {@link AggregateType}'s event stream.
 * <p>
 * <b>Thread-safety:</b> instances are mutated from the thread processing events (which advances
 * {@link #advanceResumeFromAndIncluding(GlobalEventOrder)}) and read/marked-persisted from the
 * thread persisting them (the periodic snapshotter or {@code stop()}). All access to the mutable
 * state is therefore synchronized on the instance.
 * <p>
 * <b>Dirty tracking is value-based, not flag-based:</b> "persisted" is recorded as the
 * {@link Snapshot} that was actually written to the database rather than as a boolean.
 * A boolean flag loses updates - if the resume point advances while a save is in-flight, clearing
 * the flag on commit marks the newer, never-written value as clean, and since nothing re-dirties a
 * resume point that has stopped advancing, that progress is never persisted by any later save.
 * <p>
 * <b>Reposition epoch:</b> every deliberate reposition ({@link #setResumeFromAndIncluding(GlobalEventOrder)},
 * e.g. a subscription reset) increments {@link #getRepositionEpoch()}; consumption progress
 * ({@link #advanceResumeFromAndIncluding(GlobalEventOrder)}) does not. A {@link DurableSubscriptionRepository} writes
 * the epoch together with the value and refuses a write whose epoch is older than the stored one, so a save that
 * captured the resume point <i>before</i> a reset can never overwrite the reset, whichever commits first.
 */
public final class SubscriptionResumePoint {
    private final    SubscriberId     subscriberId;
    private final    AggregateType    aggregateType;
    private volatile GlobalEventOrder resumeFromAndIncluding;
    private volatile long             repositionEpoch;
    /** The value and epoch most recently confirmed written to the underlying store - {@link #isChanged()} is derived from them. */
    private volatile Snapshot         lastPersisted;
    private volatile OffsetDateTime   lastUpdated;

    /**
     * The value of a resume point together with the reposition epoch it belongs to - what a save writes, and what
     * {@link #markAsPersisted(Snapshot, OffsetDateTime)} records. Captured atomically by {@link #snapshot()}.
     *
     * @param resumeFromAndIncluding the resume point value
     * @param repositionEpoch        the reposition epoch the value belongs to
     */
    public record Snapshot(GlobalEventOrder resumeFromAndIncluding, long repositionEpoch) {
        public Snapshot {
            requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        }
    }

    /**
     * Create a resume point in reposition epoch {@code 0}, the epoch of a resume point that has never been repositioned
     *
     * @see #SubscriptionResumePoint(SubscriberId, AggregateType, GlobalEventOrder, long, OffsetDateTime)
     */
    public SubscriptionResumePoint(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder resumeFromAndIncluding, OffsetDateTime lastUpdated) {
        this(subscriberId, aggregateType, resumeFromAndIncluding, 0L, lastUpdated);
    }

    /**
     * Create a resume point as read from, or just written to, the underlying store - it starts out persisted
     *
     * @param subscriberId           the subscriber
     * @param aggregateType          the aggregate type subscribed to
     * @param resumeFromAndIncluding the resume point value
     * @param repositionEpoch        the stored reposition epoch; must be {@code >= 0}
     * @param lastUpdated            when the value was written
     */
    public SubscriptionResumePoint(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder resumeFromAndIncluding, long repositionEpoch, OffsetDateTime lastUpdated) {
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.resumeFromAndIncluding = requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        requireTrue(repositionEpoch >= 0, "repositionEpoch must be >= 0");
        this.repositionEpoch = repositionEpoch;
        this.lastPersisted = new Snapshot(resumeFromAndIncluding, repositionEpoch);
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

    /**
     * @return how many times this resume point has been deliberately repositioned - see the class javadoc
     */
    public long getRepositionEpoch() {
        return repositionEpoch;
    }

    public OffsetDateTime getLastUpdated() {
        return lastUpdated;
    }

    /**
     * @return the current value and reposition epoch, captured together - what a save must bind
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(resumeFromAndIncluding, repositionEpoch);
    }

    /**
     * Unconditionally reposition the resume point - including <i>backwards</i> - and start a new reposition epoch.<br>
     * Use this for deliberate repositioning (e.g. a subscription reset). To record consumption
     * progress use {@link #advanceResumeFromAndIncluding(GlobalEventOrder)} instead, which cannot
     * rewind.
     */
    public synchronized SubscriptionResumePoint setResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        this.resumeFromAndIncluding = resumeFromAndIncluding;
        this.repositionEpoch++;
        return this;
    }

    /**
     * Move the resume point forward to {@code resumeFromAndIncluding}, ignoring the call when it
     * would move it backwards or leave it unchanged. Stays in the current reposition epoch.<br>
     * <br>
     * Events are not necessarily <b>completed</b> in {@link GlobalEventOrder} order: when a transient
     * gap occurs (e.g. a database disruption) the {@code EventStreamGapHandler} re-delivers the
     * skipped events afterwards, so an older event can finish after newer ones have already been
     * handled. Assigning unconditionally would rewind the resume point to that straggler and cause
     * every event in between to be redelivered on the next resume. Advancing past a gap is safe
     * because unfilled gaps are tracked separately (and durably) by the {@code EventStreamGapHandler},
     * not by the resume point - and a gap stays tracked until the subscriber is done with the event
     * filling it: until it acknowledged it (see
     * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.SubscriberAcknowledgement}),
     * or, with an event store that does not honour the acknowledgement, until the event was handed to
     * it. Only in that last case must a subscriber that is handed a gap fill and does not get to
     * handle it before it stops hold its resume point at that event instead (see
     * {@code PersistedEventSubscriber} and {@code BatchedPersistedEventSubscriber}).
     */
    public synchronized SubscriptionResumePoint advanceResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        if (resumeFromAndIncluding.longValue() > this.resumeFromAndIncluding.longValue()) {
            this.resumeFromAndIncluding = resumeFromAndIncluding;
        }
        return this;
    }

    /**
     * Mark the resume point as being in sync with the underlying store at its <i>current</i> value and epoch.
     *
     * @param lastUpdated the timestamp the value was written
     * @see #markAsPersisted(Snapshot, OffsetDateTime) to mark a specific value as written
     */
    public synchronized SubscriptionResumePoint setLastUpdated(OffsetDateTime lastUpdated) {
        return markAsPersisted(snapshot(), lastUpdated);
    }

    /**
     * Record that {@code persistedResumeFromAndIncluding} was successfully written, in the current reposition epoch.
     *
     * @param persistedResumeFromAndIncluding the value that was written to the underlying store
     * @param lastUpdated                     the timestamp the value was written
     * @deprecated cannot tell which reposition epoch the written value belonged to, so a value written before a
     * concurrent reposition is recorded against the new epoch. Capture {@link #snapshot()} before writing and use
     * {@link #markAsPersisted(Snapshot, OffsetDateTime)}
     */
    @Deprecated(forRemoval = true)
    public synchronized SubscriptionResumePoint markAsPersisted(GlobalEventOrder persistedResumeFromAndIncluding,
                                                                OffsetDateTime lastUpdated) {
        requireNonNull(persistedResumeFromAndIncluding, "No persistedResumeFromAndIncluding provided");
        return markAsPersisted(new Snapshot(persistedResumeFromAndIncluding, repositionEpoch), lastUpdated);
    }

    /**
     * Record that {@code persisted} was successfully written to the underlying store.<br>
     * <br>
     * Callers must pass the {@link #snapshot()} they actually wrote, <b>not</b> the current value: a resume point can
     * advance concurrently while the write is in-flight, and that newer value has <i>not</i> been persisted.
     * Passing it here would mark it clean and the progress would be silently lost, since the periodic
     * snapshotter only saves resume points that {@link #isChanged()}.<br>
     * A snapshot from an older reposition epoch than the one last recorded is ignored: it was overtaken by a
     * reposition that has already been persisted.
     *
     * @param persisted   the snapshot that was written to the underlying store
     * @param lastUpdated the timestamp the value was written
     */
    public synchronized SubscriptionResumePoint markAsPersisted(Snapshot persisted, OffsetDateTime lastUpdated) {
        requireNonNull(persisted, "No persisted snapshot provided");
        requireNonNull(lastUpdated, "No lastUpdated provided");
        if (persisted.repositionEpoch() < lastPersisted.repositionEpoch()) {
            return this;
        }
        this.lastPersisted = persisted;
        this.lastUpdated = lastUpdated;
        return this;
    }

    /**
     * Record that the underlying store refused {@code rejected} because it holds a newer reposition epoch - another
     * writer repositioned the resume point. The snapshot is treated as dealt with, so it is not retried on every save;
     * the next advance makes the resume point {@link #isChanged()} again. Ignored when {@code rejected} is older than
     * the epoch last recorded, i.e. when this instance's own reposition already overtook it.
     *
     * @param rejected the snapshot the underlying store refused
     */
    public synchronized SubscriptionResumePoint markAsSuperseded(Snapshot rejected) {
        requireNonNull(rejected, "No rejected snapshot provided");
        if (rejected.repositionEpoch() >= lastPersisted.repositionEpoch()) {
            this.lastPersisted = rejected;
        }
        return this;
    }

    /**
     * @return true if {@link #getResumeFromAndIncluding()} or {@link #getRepositionEpoch()} differs from what was last
     * confirmed written to the underlying store, i.e. this resume point needs saving. A reposition to the value already
     * stored still needs saving, so the store learns the new epoch
     */
    public boolean isChanged() {
        return !snapshot().equals(lastPersisted);
    }

    /**
     * @return how many {@link GlobalEventOrder} positions {@link #getResumeFromAndIncluding()} has moved
     * <i>forward</i> since the value last confirmed written - an upper bound on the number of events that
     * would be redelivered if the subscriber stopped ungracefully now. {@code 0} when the resume point
     * has not advanced, or was repositioned backwards
     */
    long unpersistedAdvance() {
        return Math.max(0, resumeFromAndIncluding.longValue() - lastPersisted.resumeFromAndIncluding().longValue());
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
                ", repositionEpoch=" + repositionEpoch +
                ", lastUpdated=" + lastUpdated +
                '}';
    }
}
