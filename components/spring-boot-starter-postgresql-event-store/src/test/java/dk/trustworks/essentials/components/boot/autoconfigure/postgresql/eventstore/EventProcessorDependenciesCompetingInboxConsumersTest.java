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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code essentials.eventstore.event-processors.competing-inbox-consumers} reaches the starter's
 * {@code EventProcessorDependencies}, and is off unless set.
 */
class EventProcessorDependenciesCompetingInboxConsumersTest {

    @Test
    void competing_inbox_consumers_are_off_by_default() {
        assertThat(dependencies(new EssentialsEventStoreProperties()).competingInboxConsumers).isFalse();
    }

    @Test
    void the_property_turns_competing_inbox_consumers_on() {
        var properties = new EssentialsEventStoreProperties();
        properties.getEventProcessors().setCompetingInboxConsumers(true);

        assertThat(dependencies(properties).competingInboxConsumers).isTrue();
    }

    private static EventProcessorDependencies dependencies(EssentialsEventStoreProperties properties) {
        return new EventStoreConfiguration().eventProcessorDependencies(mock(EventStoreSubscriptionManager.class),
                                                                        mock(Inboxes.class),
                                                                        // A final class, so a real one over mocked queues
                                                                        DurableLocalCommandBus.builder().setDurableQueues(mock(DurableQueues.class)).build(),
                                                                        List.of(),
                                                                        properties);
    }
}
