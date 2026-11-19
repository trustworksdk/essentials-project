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
package dk.trustworks.essentials.components.eventlog;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The opt-in {@code (event_type, global_order)} index of the aggregate types in
 * {@link EventLogSettings#eventTypeIndexed()}, or - {@link Kind#TENANT} - the
 * {@code (tenant, global_order)} index of those in {@link EventLogSettings#tenantIndexed()}.
 * <ul>
 *     <li>When the engine creates the schema it builds the index {@code CONCURRENTLY} on a virtual thread, on a
 *     connection of its own in autocommit, never on the start-up path: valid - done; invalid (a failed build) - drop it
 *     concurrently, then build; absent - build. A concurrent build by another JVM that wins the catalog race is logged
 *     at debug and re-checked. No advisory lock.</li>
 *     <li>Otherwise it builds nothing and logs, once per aggregate type, the statement to run.</li>
 * </ul>
 * Validity is read from {@code pg_index} ({@code indisvalid AND indisready}), never assumed. It only decides whether a
 * filtered catch-up issues the skip-ahead probe: the reads are the same with or without the index.
 */
final class EventTypeIndex {
    private static final Logger log = LoggerFactory.getLogger(EventTypeIndex.class);

    /**
     * Which opt-in index: the event-type index, or the tenant index, built, logged
     * and read the same way
     */
    enum Kind {
        EVENT_TYPE("(event_type, global_order)", "Filtered"),
        TENANT("(tenant, global_order)", "Tenant-filtered");

        private final String columns;
        private final String reader;

        Kind(String columns, String reader) {
            this.columns = columns;
            this.reader = reader;
        }

        String indexName(AggregateTypeTables tables) {
            return this == EVENT_TYPE ? tables.eventTypeIndex() : tables.tenantIndex();
        }

        List<String> statements(AggregateTypeTables tables) {
            return this == EVENT_TYPE ? EventLogSchemaContributor.eventTypeIndexStatements(tables) : EventLogSchemaContributor.tenantIndexStatements(tables);
        }
    }

    private final Kind                                          kind;

    private final DataSource                                    dataSource;
    private final BooleanSupplier                               createsSchema;
    private final ConcurrentHashMap<String, Boolean>            validity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> builds   = new ConcurrentHashMap<>();
    private final Set<String>                                   warned   = ConcurrentHashMap.newKeySet();

    /**
     * @param createsSchema whether the engine creates the schema (schema mode {@code create}); read when a build is due
     */
    EventTypeIndex(DataSource dataSource, BooleanSupplier createsSchema) {
        this(dataSource, createsSchema, Kind.EVENT_TYPE);
    }

    EventTypeIndex(DataSource dataSource, BooleanSupplier createsSchema, Kind kind) {
        this.kind = requireNonNull(kind, "No kind provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.createsSchema = requireNonNull(createsSchema, "No createsSchema provided");
    }

    /**
     * The aggregate type's tables exist: build the index in the background, or log the statement to run
     *
     * @return the build's outcome (whether the index is valid afterwards); completed at once when nothing is built
     */
    CompletableFuture<Boolean> ensure(AggregateTypeTables tables) {
        requireNonNull(tables, "No tables provided");
        var statements = kind.statements(tables);
        if (!createsSchema.getAsBoolean()) {
            if (warned.add(tables.aggregateType())) {
                log.warn("Aggregate type '{}' is configured for the {} index, but the event log does not create the schema in this " +
                                 "mode, so it does not build it. {} catch-up runs without it until it exists. Run outside a transaction: {}",
                         tables.aggregateType(), kind.columns, kind.reader, statements.getFirst());
            }
            return CompletableFuture.completedFuture(refreshQuietly(tables));
        }
        return builds.compute(tables.aggregateType(), (type, running) -> {
            if (running != null && !running.isDone()) {
                return running;
            }
            var future = new CompletableFuture<Boolean>();
            Thread.ofVirtual().name("eventlog-index-" + type + "-" + kind.name().toLowerCase(Locale.ROOT)).start(() -> {
                try {
                    future.complete(build(tables, statements));
                } catch (Throwable e) {
                    log.warn("Building the {} index {} failed; {} catch-up runs without it", kind.columns, kind.indexName(tables), kind.reader.toLowerCase(Locale.ROOT), e);
                    refreshQuietly(tables);
                    future.complete(false);
                }
            });
            return future;
        });
    }

    private boolean build(AggregateTypeTables tables, List<String> statements) throws SQLException {
        for (var attempt = 0; attempt < 3; attempt++) {
            var state = read(tables);
            if (Boolean.TRUE.equals(state)) {
                validity.put(tables.aggregateType(), true);
                return true;
            }
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    if (Boolean.FALSE.equals(state)) {
                        log.info("The {} index {} is invalid (a failed build); dropping and rebuilding it", kind.columns, kind.indexName(tables));
                        statement.execute(statements.get(1));
                    }
                    log.info("Building the {} index {} concurrently", kind.columns, kind.indexName(tables));
                    statement.execute(statements.getFirst());
                }
            } catch (SQLException e) {
                if (!"23505".equals(e.getSQLState()) && !"42P07".equals(e.getSQLState())) {
                    throw e;
                }
                log.debug("A concurrent build of {} won the catalog race; re-checking", kind.indexName(tables), e);
            }
            if (Boolean.TRUE.equals(read(tables))) {
                validity.put(tables.aggregateType(), true);
                return true;
            }
        }
        validity.put(tables.aggregateType(), false);
        return false;
    }

    /** Read the index's validity again (a type's first subscription starts) */
    boolean refresh(AggregateTypeTables tables) throws SQLException {
        var valid = Boolean.TRUE.equals(read(tables));
        validity.put(tables.aggregateType(), valid);
        return valid;
    }

    private boolean refreshQuietly(AggregateTypeTables tables) {
        try {
            return refresh(tables);
        } catch (SQLException e) {
            log.debug("Reading the validity of {} failed", kind.indexName(tables), e);
            validity.put(tables.aggregateType(), false);
            return false;
        }
    }

    /** @return {@code true} valid and ready, {@code false} present but not, {@code null} absent */
    private Boolean read(AggregateTypeTables tables) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(EventLogStorage.INDEX_VALIDITY)) {
            statement.setString(1, kind.indexName(tables));
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getBoolean(1) : null;
            }
        }
    }

    /** @return whether the aggregate type's index was valid when last read; any thread */
    boolean valid(String aggregateType) {
        return Boolean.TRUE.equals(validity.get(aggregateType));
    }

    /** @return the running or last build of {@code aggregateType}, if any; for tests */
    CompletableFuture<Boolean> build(String aggregateType) {
        return builds.get(aggregateType);
    }
}
