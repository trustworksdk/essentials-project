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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.types.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class CausationPersistableEventEnricherTest {
    private static final EventId CAUSE = EventId.of("the-cause");

    private final CausationPersistableEventEnricher enricher = new CausationPersistableEventEnricher();

    @Test
    void with_no_cause_bound_the_event_is_returned_unchanged() {
        var event = persistableEvent(null);

        assertThat(enricher.enrich(event)).isSameAs(event);
    }

    @Test
    void with_a_cause_bound_an_event_without_a_cause_gets_it_and_keeps_everything_else() {
        var event = persistableEvent(null);

        var enriched = CausationContext.where(CAUSE).call(() -> enricher.enrich(event));

        assertThat(enriched.causedByEventId()).contains(CAUSE);
        assertThat((Object) enriched.eventId()).isEqualTo(event.eventId());
        assertThat((Object) enriched.streamName()).isEqualTo(event.streamName());
        assertThat(enriched.aggregateId()).isEqualTo(event.aggregateId());
        assertThat(enriched.eventTypeOrName()).isEqualTo(event.eventTypeOrName());
        assertThat(enriched.event()).isSameAs(event.event());
        assertThat(enriched.eventOrder()).isEqualTo(event.eventOrder());
        assertThat(enriched.eventRevision()).isEqualTo(event.eventRevision());
        assertThat(enriched.metaData()).isSameAs(event.metaData());
        assertThat(enriched.timestamp()).isEqualTo(event.timestamp());
        assertThat(enriched.correlationId()).isEqualTo(event.correlationId());
        assertThat(enriched.tenant()).isEqualTo(event.tenant());
    }

    @Test
    void an_unset_timestamp_stays_unset_so_the_event_store_still_assigns_it() {
        // The builder always assigns a timestamp; a custom mapper may leave it to the event store
        var event = new PersistableEvent.DefaultPersistableEvent(EventId.random(),
                                                                 AggregateType.of("Orders"),
                                                                 OrderId.random(),
                                                                 EventTypeOrName.with(OrderEvent.OrderAccepted.class),
                                                                 new OrderEvent.OrderAccepted(OrderId.random()),
                                                                 EventOrder.of(0),
                                                                 null,
                                                                 EventMetaData.of(),
                                                                 null,
                                                                 null,
                                                                 null,
                                                                 null);
        assertThat(event.timestamp()).isEmpty();

        var enriched = CausationContext.where(CAUSE).call(() -> enricher.enrich(event));

        assertThat(enriched.causedByEventId()).contains(CAUSE);
        assertThat(enriched.timestamp()).isEmpty();
    }

    @Test
    void a_cause_the_mapper_already_set_is_never_overwritten() {
        var mappersCause = EventId.of("mappers-cause");
        var event        = persistableEvent(mappersCause);

        var enriched = CausationContext.where(CAUSE).call(() -> enricher.enrich(event));

        assertThat(enriched).isSameAs(event);
        assertThat(enriched.causedByEventId()).contains(mappersCause);
    }

    @Test
    void an_explicit_no_cause_binding_leaves_the_event_without_a_cause() {
        var event = persistableEvent(null);

        var enriched = CausationContext.where(CAUSE)
                                       .call(() -> CausationContext.where(Optional.empty()).call(() -> enricher.enrich(event)));

        assertThat(enriched).isSameAs(event);
    }

    private static PersistableEvent persistableEvent(EventId causedBy) {
        return PersistableEvent.builder()
                               .setEvent(new OrderEvent.OrderAccepted(OrderId.random()))
                               .setAggregateType(AggregateType.of("Orders"))
                               .setAggregateId(OrderId.random())
                               .setEventTypeOrName(EventTypeOrName.with(OrderEvent.OrderAccepted.class))
                               .setEventOrder(EventOrder.of(3))
                               .setTimestamp(OffsetDateTime.now())
                               .setCausedByEventId(causedBy)
                               .setCorrelationId(CorrelationId.random())
                               .setTenant(TenantId.of("tenant-1"))
                               .build();
    }
}
