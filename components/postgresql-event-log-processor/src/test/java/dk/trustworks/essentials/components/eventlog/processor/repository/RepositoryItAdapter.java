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

package dk.trustworks.essentials.components.eventlog.processor.repository;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.PersistableEventMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.Disposable;

import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * What the repository IT copies use in their {@code adapter-copy} blocks instead of
 * {@code PostgresqlEventStore}: the adapter on a started {@link EventLog} over the IT's container database, and an
 * {@link EventLog} subscription in place of the originals' {@code pollEvents} collectors.
 */
public final class RepositoryItAdapter {
    private static final String COLLECTOR = "repository-it-collector";

    private RepositoryItAdapter() {
    }

    /**
     * @return an {@link EventLogEventStore} on a new, started {@link EventLog} over {@code container}'s database; stop it
     * with {@code eventStore.eventLog().stop()}
     */
    public static EventLogEventStore eventStore(PostgreSQLContainer container,
                                                EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                PersistableEventMapper eventMapper) {
        var eventLog = EventLog.builder()
                               .setDataSource(dataSource(container))
                               .setSettings(EventLogSettings.defaults())
                               .build();
        try {
            eventLog.start();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new EventLogEventStore(eventLog, unitOfWorkFactory, EssentialsJSONEventSerializers.create(), eventMapper);
    }

    /**
     * The adapter's configuration for {@code aggregateType}, in place of an old-engine configuration passed to a repository
     */
    public static EventLogAggregateEventStreamConfiguration configuration(AggregateType aggregateType, Class<?> aggregateIdType) {
        return new EventLogAggregateEventStreamConfiguration(aggregateType,
                                                             EssentialsJSONEventSerializers.create(),
                                                             AggregateIdSerializer.serializerFor(aggregateIdType),
                                                             new TenantSerializer.NoSupportForMultiTenancySerializer());
    }

    /**
     * Drops and recreates the {@code public} schema, so the engine starts on empty tables (global order 1 again)
     */
    public static void resetSchema(PostgreSQLContainer container) {
        try (var connection = dataSource(container).getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Delivers every event of {@code aggregateType}, from global order 1, to {@code sink} as the {@link PersistedEvent}
     * the adapter loads for it (in its own unit of work)
     *
     * @return stops the subscription
     */
    public static Disposable collect(EventLogEventStore eventStore, AggregateType aggregateType, Consumer<PersistedEvent> sink) {
        var definition = SubscriptionDefinition.builder(COLLECTOR, aggregateType.toString())
                                               .perInstance("repository-it")
                                               .startFrom(StartFrom.beginning())
                                               .build();
        PeriodicHandler handler = event -> sink.accept(eventStore.getUnitOfWorkFactory()
                                                                 .withUnitOfWork(unitOfWork -> eventStore.loadEvent(aggregateType, EventId.of(event.eventId().toString()))
                                                                                                         .orElseThrow()));
        try {
            var handle = eventStore.eventLog().subscribe(definition, handler);
            return handle::stop;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PGSimpleDataSource dataSource(PostgreSQLContainer container) {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
