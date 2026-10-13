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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.PostgresqlEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.PostgresqlEventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorIT;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CdcEventStore#isAwaitedAsGap} skips its own read of the subscriber's transient gaps when its delegate is a
 * {@link PostgresqlEventStore} with the very same gap handler, because that delegate reads them. Pinned here against a
 * real {@link PostgresqlEventStore}, the way the starter wires the two: if the delegate stopped consulting the transient
 * gap rows, {@code awaitProcessed} would report a late-committing event as processed while CDC is inactive.
 */
class CdcEventStoreAwaitedGapInspectorIT extends AbstractLogicalReplicationPostgresIT {
    private static final SubscriberId SUBSCRIBER = SubscriberId.of("awaited-gap-inspector-it");

    @Test
    void a_transient_gap_is_awaited_through_the_delegate_when_both_stores_share_the_gap_handler() {
        var persistenceStrategy = new SeparateTablePerAggregateTypePersistenceStrategy(jdbi,
                                                                                       unitOfWorkFactory,
                                                                                       new EventProcessorIT.TestPersistableEventMapper(),
                                                                                       SeparateTablePerAggregateTypeEventStreamConfigurationFactory.defaultConfiguration(EssentialsJSONEventSerializers.create()));
        persistenceStrategy.addAggregateEventStreamConfiguration(ORDERS, OrderId.class);
        var sharedHandler = new PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>(unitOfWorkFactory);
        var eventStore = PostgresqlEventStore.<SeparateTablePerAggregateEventStreamConfiguration>builder()
                                             .setUnitOfWorkFactory(unitOfWorkFactory)
                                             .setPersistenceStrategy(persistenceStrategy)
                                             .setEventStreamGapHandlerFactory(store -> sharedHandler)
                                             .build();
        // CDC stays inactive: the delegate serves the subscriptions
        var cdcEventStore = new CdcEventStore<>(eventStore,
                                                unitOfWorkFactory,
                                                sharedHandler,
                                                new CdcEventBus(),
                                                new CdcProperties(),
                                                new CdcAvailability());

        unitOfWorkFactory.usingUnitOfWork(() -> sharedHandler.gapHandlerFor(SUBSCRIBER).addTransientGaps(ORDERS, LongRange.only(5)));

        assertThat(unitOfWorkFactory.withUnitOfWork(() -> cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(5)))).isTrue();
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(6)))).isFalse();
        assertThat(unitOfWorkFactory.withUnitOfWork(() -> cdcEventStore.isAwaitedAsGap(SubscriberId.of("another-subscriber"), ORDERS, GlobalEventOrder.of(5)))).isFalse();
    }
}
