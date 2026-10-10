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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

/**
 * Role interface of an {@link EventStore} that can tell whether a subscriber still awaits a {@link GlobalEventOrder} as a
 * gap: an order its subscription moved its resume point past while the event was not committed (or not visible) yet,
 * and whose event it will still be handed when it commits - a gap fill, delivered after later events.
 * <p>
 * Used by {@code AbstractEventProcessor#awaitProcessed}: a subscription's resume point only advances, so "the resume
 * point has moved past the event" does not mean the subscriber is done with it while it is still awaited as a gap.
 * An event store that does not implement this interface cannot be asked, and {@code awaitProcessed} then reports such
 * an event as processed as soon as the resume point has moved past it (it logs a WARN once per processor).
 * <p>
 * The event stores Essentials provides implement it: {@link PostgresqlEventStore} from its gap handler's transient gaps
 * and the middles of wide gaps its polls await in memory only, and
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcEventStore} by forwarding to the
 * event store it decorates. <b>An event store that decorates one of them must implement it too, and forward to its
 * delegate</b> - the same as {@link EventStore#forgetGapMiddlesAwaitedInMemory(SubscriberId, AggregateType)}.
 */
public interface AwaitedGapInspector {
    /**
     * @param subscriberId     the subscriber
     * @param aggregateType    the aggregate type it subscribes to
     * @param globalEventOrder the global event order of an event of that aggregate type
     * @return true if the subscriber still awaits {@code globalEventOrder} as a gap - its subscription moved past it and
     * will still hand its event to the subscriber - false if not, or if this event store cannot tell
     */
    boolean isAwaitedAsGap(SubscriberId subscriberId, AggregateType aggregateType, GlobalEventOrder globalEventOrder);
}
