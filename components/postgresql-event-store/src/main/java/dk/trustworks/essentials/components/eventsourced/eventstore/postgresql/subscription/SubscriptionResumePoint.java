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

public final class SubscriptionResumePoint {
    private final SubscriberId     subscriberId;
    private final AggregateType    aggregateType;
    private       GlobalEventOrder resumeFromAndIncluding;
    private       OffsetDateTime   lastUpdated;
    private       boolean          changed;

    public SubscriptionResumePoint(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder resumeFromAndIncluding, OffsetDateTime lastUpdated) {
        this.subscriberId = requireNonNull(subscriberId, "No subscriberId provided");
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.resumeFromAndIncluding = requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
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
    public SubscriptionResumePoint setResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        if (!Objects.equals(this.resumeFromAndIncluding, resumeFromAndIncluding)) {
            changed = true;
        }
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
    public SubscriptionResumePoint advanceResumeFromAndIncluding(GlobalEventOrder resumeFromAndIncluding) {
        requireNonNull(resumeFromAndIncluding, "No resumeFromAndIncluding provided");
        if (resumeFromAndIncluding.longValue() <= this.resumeFromAndIncluding.longValue()) {
            return this;
        }
        return setResumeFromAndIncluding(resumeFromAndIncluding);
    }

    public SubscriptionResumePoint setLastUpdated(OffsetDateTime lastUpdated) {
        this.lastUpdated = requireNonNull(lastUpdated, "No lastUpdated provided");
        changed = false;
        return this;
    }

    public boolean isChanged() {
        return changed;
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
