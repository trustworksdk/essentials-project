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
package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The reader half of {@link TradingDemoEventLogPartitionedViewProcessor}: an {@link EventLogReactor} ({@code FRESH},
 * per-aggregate order, one instance at a time) whose single handler takes every event and hands it on (the inbox twins
 * reads through {@link EventLogInboxProcessor} instead). It is not a bean: the twin that owns it starts and stops it.
 * <p>
 * Kept apart from the twin because {@link EventLogReactor} dispatches to the {@code @MessageHandler} methods of
 * {@code this}: were the twin itself the reactor, the reactor would call the twin's own handlers directly instead of
 * forwarding.
 */
final class EventLogForwarder extends EventLogReactor {
    /** {@link MessageMetaData} key of the forwarded event's id: the consumer binds it as the cause */
    static final String EVENT_ID_METADATA_KEY = EventLogInboxProcessor.EVENT_ID_METADATA_KEY;

    private final Consumer<DeliveredEvent> forward;

    EventLogForwarder(String name, List<AggregateType> aggregateTypes, EventLogProcessorDependencies dependencies, Consumer<DeliveredEvent> forward) {
        super(name, aggregateTypes.stream().map(AggregateType::toString).toList(), dependencies);
        this.forward = requireNonNull(forward, "No forward provided");
    }

    @MessageHandler
    void handle(Object payload, DeliveredEvent event) {
        forward.accept(event);
    }

    /**
     * @return the event's metadata as message metadata, plus its event id under {@link #EVENT_ID_METADATA_KEY}
     */
    static MessageMetaData messageMetaData(DeliveredEvent event) {
        var metaData = new HashMap<String, String>(event.metaData());
        metaData.put(EVENT_ID_METADATA_KEY, event.eventId().toString());
        return new MessageMetaData(metaData);
    }

    /**
     * An event forwarded with its payload, identified by aggregate id and event order, so a re-forward of one still
     * queued (the reader resumes at its last recorded progress) is absorbed by a queue that keeps them unique.
     */
    static final class ForwardedEvent extends OrderedMessage implements IdentifiedByKeyAndOrder {
        @SuppressWarnings("PMD.LooseCoupling") // MessageMetaData is the type OrderedMessage's constructor takes
        ForwardedEvent(Object payload, String key, long order, MessageMetaData metaData) {
            super(payload, key, order, metaData);
        }
    }
}
