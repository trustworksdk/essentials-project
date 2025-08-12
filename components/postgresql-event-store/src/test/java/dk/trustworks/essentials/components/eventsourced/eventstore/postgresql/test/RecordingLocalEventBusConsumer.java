/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.PersistedEvent;
import dk.trustworks.essentials.reactive.EventHandler;

import java.util.*;

/**
 * Shared test implementation of EventHandler for recording events from the local event bus.
 * This class provides a consistent way to capture and verify events across integration tests.
 */
public class RecordingLocalEventBusConsumer implements EventHandler {

    public final List<PersistedEvent> beforeCommitPersistedEvents = new ArrayList<>();
    public final List<PersistedEvent> afterCommitPersistedEvents = new ArrayList<>();
    public final List<PersistedEvent> afterRollbackPersistedEvents = new ArrayList<>();

    @Override
    public void handle(Object event) {
        var persistedEvents = (PersistedEvents) event;
        if (persistedEvents.commitStage == CommitStage.BeforeCommit) {
            beforeCommitPersistedEvents.addAll(persistedEvents.events);
        } else if (persistedEvents.commitStage == CommitStage.AfterCommit) {
            afterCommitPersistedEvents.addAll(persistedEvents.events);
        } else {
            afterRollbackPersistedEvents.addAll(persistedEvents.events);
        }
    }

    /**
     * Clears all recorded events
     */
    public void clear() {
        beforeCommitPersistedEvents.clear();
        afterCommitPersistedEvents.clear();
        afterRollbackPersistedEvents.clear();
    }
}
