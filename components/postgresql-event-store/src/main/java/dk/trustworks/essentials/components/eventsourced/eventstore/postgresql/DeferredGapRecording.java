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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

/**
 * Role interface of an {@link EventStore} that can keep the gaps a subscription opens in memory - instead of recording
 * each with the gap handler the moment it opens - for as long as the subscriber's resume point is not saved past them.
 * <p>
 * A gap is recorded durably (as a transient gap) so that a subscription that restarts from a saved resume point still
 * waits for an event that committed late, below that resume point. Until a resume point past the gap is saved, a restart
 * starts below the gap and reads its event anyway - so the record is only needed from the moment such a resume point is
 * saved. Under concurrent appenders almost every gap fills milliseconds after it opened, long before the next save, and
 * then costs no database write at all.
 * <p>
 * The contract, for whoever saves the subscriber's resume points: having called
 * {@link #deferGapRecordingUntilResumePointSave(SubscriberId, AggregateType)} for a subscriber and aggregate type, it must
 * call {@link #beforeResumePointSave(SubscriberId, AggregateType)} before <i>every</i> save of that resume point - before
 * it reads the value it is going to write - and close the returned {@link ResumePointSave} once the save is done (or
 * failed). The {@code DefaultEventStoreSubscriptionManager} does so for every subscription it manages, through the
 * {@code DurableSubscriptionRepository} it saves resume points with. A subscriber nobody registered keeps the gaps it opens
 * recorded at once.
 * <p>
 * Implemented by {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStore}. An event
 * store that decorates it and does not implement this interface (or does not forward it) simply turns the deferral off.
 */
public interface DeferredGapRecording {
    /**
     * From now on the subscriptions of {@code subscriberId} to {@code aggregateType} that start may keep the gaps they open
     * in memory until {@link #beforeResumePointSave(SubscriberId, AggregateType)} - the caller promises to call it before
     * every save of the subscriber's resume point
     *
     * @param subscriberId  the subscriber
     * @param aggregateType the aggregate type it subscribes to
     */
    void deferGapRecordingUntilResumePointSave(SubscriberId subscriberId, AggregateType aggregateType);

    /**
     * Records durably every gap of the subscriber's running subscriptions still kept in memory, and records every gap they
     * open from now on at once, until the returned {@link ResumePointSave} is closed - so that the resume point the caller
     * is about to read and save can be past no gap that is not recorded.
     *
     * @param subscriberId  the subscriber
     * @param aggregateType the aggregate type it subscribes to
     * @return to close once the resume point was saved, or the save failed
     * @throws RuntimeException the gaps could not be recorded - the caller must then not save the resume point
     */
    ResumePointSave beforeResumePointSave(SubscriberId subscriberId, AggregateType aggregateType);

    /**
     * The window of one resume point save - see {@link #beforeResumePointSave(SubscriberId, AggregateType)}
     */
    interface ResumePointSave extends AutoCloseable {
        /**
         * The save is done: gaps opened from now on may be kept in memory again
         */
        @Override
        void close();
    }
}
