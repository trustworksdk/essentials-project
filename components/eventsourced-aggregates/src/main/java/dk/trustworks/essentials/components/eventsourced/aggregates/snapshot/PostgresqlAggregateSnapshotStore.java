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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.reflection.Classes;
import dk.trustworks.essentials.types.NumberType;
import io.micrometer.core.instrument.MeterRegistry;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.slf4j.*;

import java.sql.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.*;
import static dk.trustworks.essentials.shared.MessageFormatter.*;
import static dk.trustworks.essentials.shared.MessageFormatter.NamedArgumentBinding.arg;

/**
 * An implementation of {@code AggregateSnapshotStore} that uses PostgreSQL as the underlying storage
 * for aggregate snapshots. This class provides methods for storing, retrieving, and deleting snapshots
 * of aggregates in a PostgreSQL database. It integrates with an event store, a unit-of-work factory,
 * and JSON serialization for managing snapshots efficiently.
 * <p>
 * The class allows optional integration with a metrics registry for operational measurement support
 * and supports configurable snapshot table naming.
 * <p>
 * This implementation is designed to work with aggregate types and their snapshots, allowing for
 * storage, retrieval, and deletion of both individual and bulk snapshots in a type-safe manner.
 */
@SuppressWarnings("unchecked")
public class PostgresqlAggregateSnapshotStore implements AggregateSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlAggregateSnapshotStore.class);

    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork>       unitOfWorkFactory;
    private final String                                                              snapshotTableName;
    private final JSONEventSerializer                                                 jsonSerializer;
    private final AggregateSnapshotStateAdapter                                       snapshotStateAdapter;
    private final AggregateSnapshotMeasurementSupport                                 measurementSupport;
    private final AggregateSnapshotRowMapper                                          aggregateSnapshotWithSnapshotPayloadRowMapper;
    private final AggregateSnapshotRowMapper                                          aggregateSnapshotWithoutSnapshotPayloadRowMapper;

    /**
     * Constructs a new instance of the {@code PostgresqlAggregateSnapshotStore}.
     *
     * @param eventStore The event store used for managing aggregate events and their configurations.
     * @param unitOfWorkFactory The factory that provides {@link HandleAwareUnitOfWork} objects for database operations.
     * @param snapshotTableName The optional name of the database table used for storing aggregate snapshots.
     * @param jsonSerializer The serializer used for converting events and snapshots to and from JSON format.
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public PostgresqlAggregateSnapshotStore(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                            Optional<String> snapshotTableName,
                                            JSONEventSerializer jsonSerializer) {
        this(eventStore,
             unitOfWorkFactory,
             snapshotTableName,
             jsonSerializer,
             Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    /**
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    public PostgresqlAggregateSnapshotStore(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                            Optional<String> snapshotTableName,
                                            JSONEventSerializer jsonSerializer,
                                            Optional<MeterRegistry> meterRegistryOptional) {
        this.eventStore = requireNonNull(eventStore, "No eventStore instance provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory instance provided");
        this.snapshotTableName = requireNonNull(snapshotTableName, "No snapshotTableName provided")
                .orElse(PostgresqlAggregateSnapshotRepository.DEFAULT_AGGREGATE_SNAPSHOTS_TABLE_NAME).toLowerCase();
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer instance provided");
        this.snapshotStateAdapter = new DefaultAggregateSnapshotStateAdapter(this.jsonSerializer);
        this.measurementSupport = new AggregateSnapshotMeasurementSupport(meterRegistryOptional);
        aggregateSnapshotWithSnapshotPayloadRowMapper = new AggregateSnapshotRowMapper(true);
        aggregateSnapshotWithoutSnapshotPayloadRowMapper = new AggregateSnapshotRowMapper(false);
        initializeStorage();
    }

    private void initializeStorage() {
        PostgresqlUtil.checkIsValidTableOrColumnName(snapshotTableName);
        // Holds the framework's bootstrap lock: CREATE ... IF NOT EXISTS is not atomic against concurrent sessions, so
        // two JVMs starting together can both see "doesn't exist" and one fails on a duplicate catalog entry. See
        // PostgresqlUtil#acquireBootstrapLock.
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            PostgresqlUtil.acquireBootstrapLock(uow.handle());
            uow.handle().execute(bind("""
                                      CREATE TABLE IF NOT EXISTS {:tableName} (
                                          aggregate_impl_type TEXT NOT NULL,
                                          aggregate_id TEXT NOT NULL,
                                          aggregate_type TEXT NOT NULL,
                                          last_included_event_order bigint NOT NULL,
                                          snapshot JSONB NOT NULL,
                                          created_ts TIMESTAMP WITH TIME ZONE NOT NULL,
                                          statistics JSONB,
                                          PRIMARY KEY (aggregate_type,
                                                       aggregate_impl_type,
                                                       aggregate_id,
                                                       last_included_event_order)
                                      )""", arg("tableName", snapshotTableName)));
        });
        log.info("Ensured that aggregate snapshot table '{}' exists", snapshotTableName);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> Optional<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadSnapshot(AggregateType aggregateType,
                                                                                                       ID aggregateId,
                                                                                                       EventOrder withLastIncludedEventOrderLessThanOrEqualTo,
                                                                                                       Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(withLastIncludedEventOrderLessThanOrEqualTo, "No withLastIncludedEventOrderLessThanOrEqualTo supplied");
        requireNonNull(aggregateImplType, "No aggregateImplType supplied");
        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);
        return measurementSupport.recordLoadSnapshot(aggregateType,
                                                     aggregateImplType,
                                                     () -> unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                                                                     SELECT *
                                                                                                                                     FROM {:tableName}
                                                                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                                                                       AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                       AND aggregate_id = :aggregate_id
                                                                                                                                       AND last_included_event_order <= :last_included_event_order
                                                                                                                                     ORDER BY last_included_event_order DESC
                                                                                                                                     LIMIT 1
                                                                                                                                     """, arg("tableName", snapshotTableName)))
                                                                                                      .bind("aggregate_type", aggregateType.value())
                                                                                                      .bind("aggregate_impl_type", aggregateImplType.getName())
                                                                                                      .bind("aggregate_id", serializedAggregateId)
                                                                                                      .bind("last_included_event_order", withLastIncludedEventOrderLessThanOrEqualTo)
                                                                                                      .map(aggregateSnapshotWithSnapshotPayloadRowMapper)
                                                                                                      .map(snapshot -> (AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>) snapshot)
                                                                                                      .findOne()));
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> List<AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>> loadAllSnapshots(AggregateType aggregateType,
                                                                                                       ID aggregateId,
                                                                                                       Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                                                                       boolean includeSnapshotPayload) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(aggregateImplType, "No aggregateImplType supplied");
        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);
        var selectColumns         = includeSnapshotPayload ? "*" : "aggregate_impl_type, aggregate_id, aggregate_type, last_included_event_order, created_ts, statistics";

        return measurementSupport.recordLoadAllSnapshots(aggregateType,
                                                         aggregateImplType,
                                                         includeSnapshotPayload,
                                                         () -> unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                                                                     SELECT {:selectColumns}
                                                                                                                                     FROM {:tableName}
                                                                                                                                     WHERE aggregate_type = :aggregate_type
                                                                                                                                       AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                       AND aggregate_id = :aggregate_id
                                                                                                                                     ORDER BY last_included_event_order ASC
                                                                                                                                     """,
                                                                                                                                     arg("selectColumns", selectColumns),
                                                                                                                                     arg("tableName", snapshotTableName)))
                                                                                                          .bind("aggregate_type", aggregateType.value())
                                                                                                          .bind("aggregate_impl_type", aggregateImplType.getName())
                                                                                                          .bind("aggregate_id", serializedAggregateId)
                                                                                                          .map(includeSnapshotPayload ? aggregateSnapshotWithSnapshotPayloadRowMapper : aggregateSnapshotWithoutSnapshotPayloadRowMapper)
                                                                                                          .map(snapshot -> (AggregateSnapshot<ID, AGGREGATE_IMPL_TYPE>) snapshot)
                                                                                                          .list()));
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> Optional<EventOrder> findMostRecentLastIncludedEventOrder(AggregateType aggregateType,
                                                                                               ID aggregateId,
                                                                                               Class<AGGREGATE_IMPL_TYPE> aggregateImplType) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(aggregateImplType, "No aggregateImplType supplied");
        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);

        return measurementSupport.recordFindMostRecentLastIncludedEventOrder(aggregateType,
                                                                             aggregateImplType,
                                                                             () -> unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery(bind("""
                                                                                                                                                             SELECT coalesce(MAX(last_included_event_order), -1)
                                                                                                                                                             FROM {:tableName}
                                                                                                                                                             WHERE aggregate_type = :aggregate_type
                                                                                                                                                               AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                               AND aggregate_id = :aggregate_id
                                                                                                                                                             """, arg("tableName", snapshotTableName)))
                                                                                                                              .bind("aggregate_type", aggregateType.value())
                                                                                                                              .bind("aggregate_impl_type", aggregateImplType.getName())
                                                                                                                              .bind("aggregate_id", serializedAggregateId)
                                                                                                                              .mapTo(EventOrder.class)
                                                                                                                              .findOne()));
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void saveSnapshot(AggregateType aggregateType,
                                                       ID aggregateId,
                                                       Class<AGGREGATE_IMPL_TYPE> aggregateImplType,
                                                       EventOrder lastIncludedEventOrder,
                                                       String serializedSnapshot) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(aggregateImplType, "No aggregateImplType supplied");
        requireNonNull(lastIncludedEventOrder, "No lastIncludedEventOrder supplied");
        requireNonNull(serializedSnapshot, "No serializedSnapshot supplied");
        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);

        final int[] rowsUpdated = new int[1];
        measurementSupport.recordSaveSnapshot(aggregateType,
                                              aggregateImplType,
                                              () -> rowsUpdated[0] = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                                                                               INSERT INTO {:tableName} (
                                                                                                                                                   aggregate_impl_type,
                                                                                                                                                   aggregate_id,
                                                                                                                                                   aggregate_type,
                                                                                                                                                   last_included_event_order,
                                                                                                                                                   snapshot,
                                                                                                                                                   created_ts
                                                                                                                                               )
                                                                                                                                               SELECT :aggregate_impl_type,
                                                                                                                                                      :aggregate_id,
                                                                                                                                                      :aggregate_type,
                                                                                                                                                      :last_included_event_order,
                                                                                                                                                      :snapshot::jsonb,
                                                                                                                                                      :created_ts
                                                                                                                                               WHERE NOT EXISTS (
                                                                                                                                                   SELECT 1 FROM {:tableName}
                                                                                                                                                   WHERE aggregate_type = :aggregate_type
                                                                                                                                                     AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                     AND aggregate_id = :aggregate_id
                                                                                                                                                     AND last_included_event_order > :last_included_event_order
                                                                                                                                               )
                                                                                                                                               ON CONFLICT DO NOTHING
                                                                                                                                               """, arg("tableName", snapshotTableName)))
                                                                                                                .bind("aggregate_impl_type", aggregateImplType.getName())
                                                                                                                .bind("aggregate_id", serializedAggregateId)
                                                                                                                .bind("aggregate_type", aggregateType.value())
                                                                                                                .bind("last_included_event_order", lastIncludedEventOrder.longValue())
                                                                                                                .bind("snapshot", serializedSnapshot)
                                                                                                                .bind("created_ts", OffsetDateTime.now())
                                                                                                                .execute()));

        if (rowsUpdated[0] == 1) {
            log.debug("[{}:{}] Saved Aggregate Snapshot for '{}' and last_included_event_order {}",
                      aggregateType,
                      aggregateId,
                      aggregateImplType.getName(),
                      lastIncludedEventOrder);
        } else {
            log.debug("[{}:{}] Skipped saving Aggregate Snapshot for '{}' at last_included_event_order {} - a newer or equal snapshot already exists",
                      aggregateType,
                      aggregateId,
                      aggregateImplType.getName(),
                      lastIncludedEventOrder);
        }
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshotsOlderThan(AggregateType aggregateType,
                                                                    ID aggregateId,
                                                                    Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType,
                                                                    EventOrder olderThanEventOrder) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(withAggregateImplementationType, "No withAggregateImplementationType supplied");
        requireNonNull(olderThanEventOrder, "No olderThanEventOrder supplied");

        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);
        final int[] rowsUpdated = new int[1];
        measurementSupport.recordDeleteSnapshots(aggregateType,
                                                 withAggregateImplementationType,
                                                 "older_than",
                                                 () -> rowsUpdated[0] = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                                                                                   DELETE FROM {:tableName}
                                                                                                                                                   WHERE aggregate_type = :aggregate_type
                                                                                                                                                     AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                     AND aggregate_id = :aggregate_id
                                                                                                                                                     AND last_included_event_order < :older_than_event_order
                                                                                                                                                   """, arg("tableName", snapshotTableName)))
                                                                                                                   .bind("aggregate_type", aggregateType.value())
                                                                                                                   .bind("aggregate_impl_type", withAggregateImplementationType.getName())
                                                                                                                   .bind("aggregate_id", serializedAggregateId)
                                                                                                                   .bind("older_than_event_order", olderThanEventOrder.longValue())
                                                                                                                   .execute()));
        log.debug("Deleted {} historic snapshots related to Aggregate '{}' with id '{}' and last_included_event_order < {}",
                  rowsUpdated[0],
                  withAggregateImplementationType.getName(),
                  aggregateId,
                  olderThanEventOrder);
    }

    /**
     * The one operation that deliberately spans {@link AggregateType}s: it is scoped to an aggregate implementation
     * type, so if the same class is registered under several aggregate types this removes the snapshots of all of them.
     * Every other operation here takes an {@link AggregateType} and is scoped to it.
     */
    @Override
    public <AGGREGATE_IMPL_TYPE> void deleteAllSnapshots(Class<AGGREGATE_IMPL_TYPE> ofAggregateImplementationType) {
        requireNonNull(ofAggregateImplementationType, "No ofAggregateImplementationType supplied");
        final int[] rowsUpdated = new int[1];
        measurementSupport.recordDeleteAllSnapshots(ofAggregateImplementationType,
                                                    () -> rowsUpdated[0] = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                                                                                      DELETE FROM {:tableName}
                                                                                                                                                      WHERE aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                      """, arg("tableName", snapshotTableName)))
                                                                                                                      .bind("aggregate_impl_type", ofAggregateImplementationType.getName())
                                                                                                                      .execute()));
        log.debug("Deleted {} historic snapshots related to Aggregate implementation type '{}'",
                  rowsUpdated[0],
                  ofAggregateImplementationType.getName());
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType,
                                                          ID aggregateId,
                                                          Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(withAggregateImplementationType, "No withAggregateImplementationType supplied");

        var config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);

        final int[] rowsUpdated = new int[1];
        measurementSupport.recordDeleteSnapshots(aggregateType,
                                                 withAggregateImplementationType,
                                                 "all",
                                                 () -> rowsUpdated[0] = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                                                                                   DELETE FROM {:tableName}
                                                                                                                                                   WHERE aggregate_type = :aggregate_type
                                                                                                                                                     AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                     AND aggregate_id = :aggregate_id
                                                                                                                                                   """, arg("tableName", snapshotTableName)))
                                                                                                                   .bind("aggregate_type", aggregateType.value())
                                                                                                                   .bind("aggregate_impl_type", withAggregateImplementationType.getName())
                                                                                                                   .bind("aggregate_id", serializedAggregateId)
                                                                                                                   .execute()));
        log.debug("Deleted {} historic snapshots related to Aggregate '{}' with id '{}'",
                  rowsUpdated[0],
                  withAggregateImplementationType.getName(),
                  aggregateId);
    }

    @Override
    public <ID, AGGREGATE_IMPL_TYPE> void deleteSnapshots(AggregateType aggregateType,
                                                          ID aggregateId,
                                                          Class<AGGREGATE_IMPL_TYPE> withAggregateImplementationType,
                                                          List<EventOrder> snapshotEventOrdersToDelete) {
        requireNonNull(aggregateType, "No aggregateType supplied");
        requireNonNull(aggregateId, "No aggregateId supplied");
        requireNonNull(withAggregateImplementationType, "No withAggregateImplementationType supplied");
        requireNonEmpty(snapshotEventOrdersToDelete, "snapshotEventOrdersToDelete may not be null or empty");

        var         config                = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var         serializedAggregateId = config.aggregateIdSerializer.serialize(aggregateId);
        final int[] rowsUpdated           = new int[1];
        measurementSupport.recordDeleteSnapshots(aggregateType,
                                                 withAggregateImplementationType,
                                                 "selected",
                                                 () -> rowsUpdated[0] = unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createUpdate(bind("""
                                                                                                                                                   DELETE FROM {:tableName}
                                                                                                                                                   WHERE aggregate_type = :aggregate_type
                                                                                                                                                     AND aggregate_impl_type = :aggregate_impl_type
                                                                                                                                                     AND aggregate_id = :aggregate_id
                                                                                                                                                     AND last_included_event_order IN (<snapshotEventOrdersToDelete>)
                                                                                                                                                   """, arg("tableName", snapshotTableName)))
                                                                                                                   .bind("aggregate_type", aggregateType.value())
                                                                                                                   .bind("aggregate_impl_type", withAggregateImplementationType.getName())
                                                                                                                   .bind("aggregate_id", serializedAggregateId)
                                                                                                                   .bindList("snapshotEventOrdersToDelete", snapshotEventOrdersToDelete.stream().map(NumberType::longValue).collect(Collectors.toList()))
                                                                                                                   .execute()));
        log.debug("Deleted {} historic snapshots related to Aggregate '{}' with id '{}' and snapshotEventOrdersToDelete: {}",
                  rowsUpdated[0],
                  withAggregateImplementationType.getName(),
                  aggregateId,
                  snapshotEventOrdersToDelete);
    }

    private class AggregateSnapshotRowMapper implements RowMapper<AggregateSnapshot> {
        private final boolean resultSetContainsSnapshotPayload;

        private AggregateSnapshotRowMapper(boolean resultSetContainsSnapshotPayload) {
            this.resultSetContainsSnapshotPayload = resultSetContainsSnapshotPayload;
        }

        @Override
        public AggregateSnapshot map(ResultSet rs, StatementContext ctx) throws SQLException {
            var aggregateType     = AggregateType.of(rs.getString("aggregate_type"));
            var config            = eventStore.getAggregateEventStreamConfiguration(aggregateType);
            var aggregateImplType = Classes.forName(rs.getString("aggregate_impl_type"), jsonSerializer.getClassLoader());

            var aggregateId     = config.aggregateIdSerializer.deserialize(rs.getString("aggregate_id"));
            var snapshotPayload = deserializeSnapshot(rs, aggregateId, aggregateImplType);
            return new AggregateSnapshot(aggregateType,
                                         aggregateId,
                                         aggregateImplType,
                                         snapshotPayload,
                                         EventOrder.of(rs.getLong("last_included_event_order")));
        }

        private Object deserializeSnapshot(ResultSet rs, Object aggregateId, Class<?> aggregateImplType) throws SQLException {
            var startedAt = System.nanoTime();
            var outcome   = "success";
            try {
                var eventOrderOfLastIncludedEvent = EventOrder.of(rs.getLong("last_included_event_order"));
                return resultSetContainsSnapshotPayload ? snapshotStateAdapter.deserializeSnapshotState(rs.getString("snapshot"),
                                                                                                        aggregateImplType,
                                                                                                        aggregateId,
                                                                                                        eventOrderOfLastIncludedEvent) : null;
            } catch (Exception e) {
                outcome = "failure";
                log.error(msg("Failed to deserialize '{}' with id '{}'", aggregateImplType, aggregateId), e);
                return new BrokenSnapshot(e);
            } finally {
                measurementSupport.recordDeserializeSnapshot(AggregateType.of(rs.getString("aggregate_type")),
                                                             aggregateImplType,
                                                             outcome,
                                                             Duration.ofNanos(System.nanoTime() - startedAt));
            }
        }
    }

    /**
     * Creates a builder for a {@link PostgresqlAggregateSnapshotStore}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link PostgresqlAggregateSnapshotStore}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload for Spring {@code @Bean} methods.
     */
    public static final class Builder {
        private ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private String snapshotTableName;
        private JSONEventSerializer jsonSerializer;
        private MeterRegistry meterRegistryOptional;

        /**
         * @param eventStore required
         * @return this builder
         */
        public Builder setEventStore(ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param snapshotTableName optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setSnapshotTableName(String snapshotTableName) {
            this.snapshotTableName = snapshotTableName;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setSnapshotTableName(String)}.
         *
         * @param snapshotTableName the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setSnapshotTableName(Optional<String> snapshotTableName) {
            requireNonNull(snapshotTableName, "No snapshotTableName provided");
            return setSnapshotTableName(snapshotTableName.orElse(null));
        }

        /**
         * @param jsonSerializer required
         * @return this builder
         */
        public Builder setJsonSerializer(JSONEventSerializer jsonSerializer) {
            this.jsonSerializer = jsonSerializer;
            return this;
        }

        /**
         * @param meterRegistryOptional optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setMeterRegistry(MeterRegistry meterRegistryOptional) {
            this.meterRegistryOptional = meterRegistryOptional;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}.
         *
         * @param meterRegistryOptional the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setMeterRegistry(Optional<MeterRegistry> meterRegistryOptional) {
            requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
            return setMeterRegistry(meterRegistryOptional.orElse(null));
        }

        /**
         * @return the new {@link PostgresqlAggregateSnapshotStore}
         */
        @SuppressWarnings("removal")
        public PostgresqlAggregateSnapshotStore build() {
            return new PostgresqlAggregateSnapshotStore(eventStore,
                                                        unitOfWorkFactory,
                                                        Optional.ofNullable(snapshotTableName),
                                                        jsonSerializer,
                                                        Optional.ofNullable(meterRegistryOptional));
        }
    }

}
