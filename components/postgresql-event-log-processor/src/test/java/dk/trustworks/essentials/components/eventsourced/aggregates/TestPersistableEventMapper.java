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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.types.*;

import java.time.OffsetDateTime;

/**
 * Shared test implementation of PersistableEventMapper for use across integration tests.
 * This class provides a consistent way to map events to persistable events with test metadata.
 */
public class TestPersistableEventMapper implements PersistableEventMapper {

    /**
     * Default metadata used in tests
     */
    public static final EventMetaData META_DATA = EventMetaData.of("Key1", "Value1", "Key2", "Value2");

    public final CorrelationId correlationId   = CorrelationId.random();
    public final EventId       causedByEventId = EventId.random();

    /**
     * Creates a new TestPersistableEventMapper with default metadata
     */
    public TestPersistableEventMapper() {
    }

    /**
     * Creates a new TestPersistableEventMapper with empty metadata
     */
    public static TestPersistableEventMapper withEmptyMetadata() {
        TestPersistableEventMapper mapper = new TestPersistableEventMapper();
        return new TestPersistableEventMapper() {
            @Override
            public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
                return PersistableEvent.from(EventId.random(),
                                             aggregateEventStreamConfiguration.aggregateType,
                                             aggregateId,
                                             EventTypeOrName.with(event.getClass()),
                                             event,
                                             eventOrder,
                                             EventRevision.of(1),
                                             new EventMetaData(),
                                             OffsetDateTime.now(),
                                             mapper.causedByEventId,
                                             mapper.correlationId,
                                             null);
            }
        };
    }

    @Override
    public PersistableEvent map(Object aggregateId, AggregateEventStreamConfiguration aggregateEventStreamConfiguration, Object event, EventOrder eventOrder) {
        return PersistableEvent.from(EventId.random(),
                                     aggregateEventStreamConfiguration.aggregateType,
                                     aggregateId,
                                     EventTypeOrName.with(event.getClass()),
                                     event,
                                     eventOrder,
                                     EventRevision.of(1),
                                     META_DATA,
                                     OffsetDateTime.now(),
                                     causedByEventId,
                                     correlationId,
                                     null);
    }
}
