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

import dk.trustworks.essentials.components.eventlog.ErrorSettings;
import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

import java.util.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The reader half of {@link TradingDemoEventLogInboxProcessor}: an {@link EventLogViewProcessor} ({@code CONTIGUOUS}, from
 * the beginning, one instance at a time) whose single handler takes every event and hands it on. Each page of up to
 * {@code pageSize} events runs in one page transaction, so everything the handler enqueues commits atomically with the
 * subscription's progress - one transaction per page instead of one per event. It is not a bean: the twin that owns it
 * starts and stops it.
 * <p>
 * Kept apart from the twin because {@link EventLogViewProcessor} dispatches to the {@code @MessageHandler} methods of
 * {@code this}: were the twin itself the view processor, it would call the twin's own handlers directly instead of
 * forwarding.
 */
final class EventLogPageForwarder extends EventLogViewProcessor {
    private final Consumer<DeliveredEvent> forward;
    private final ErrorSettings            errorSettings;

    EventLogPageForwarder(String name, List<AggregateType> aggregateTypes, EventLogProcessorDependencies dependencies, int pageSize, Consumer<DeliveredEvent> forward) {
        super(name, aggregateTypes.stream().map(AggregateType::toString).toList(), dependencies);
        this.forward = requireNonNull(forward, "No forward provided");
        var defaults = ErrorSettings.defaults(dependencies.eventLog().settings());
        this.errorSettings = new ErrorSettings(defaults.retryAttempts(), defaults.backoffStart(), defaults.backoffMax(), pageSize);
    }

    /** The engine's retry and backoff defaults, with the twin's page size */
    @Override
    protected Optional<ErrorSettings> errorSettings() {
        return Optional.of(errorSettings);
    }

    @MessageHandler
    void handle(Object payload, DeliveredEvent event) {
        forward.accept(event);
    }
}
