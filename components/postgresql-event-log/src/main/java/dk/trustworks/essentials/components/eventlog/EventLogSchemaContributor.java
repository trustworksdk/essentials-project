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

import dk.trustworks.essentials.components.foundation.schema.*;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.components.eventlog.EventLogSchema.*;
import static dk.trustworks.essentials.components.foundation.postgresql.PostgresqlSchemaStatements.createIndexUnlessPresent;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The event log's schema as an {@link EssentialsSchemaHarness} contribution, and the one place its DDL text lives
 * (the schema harness rule: DDL lives in schema contributors). Design: {@code docs/plans/event-store-engine-phase2.md} §2.
 * <p>
 * The fixed schema ({@code el_event_types}, {@code el_aggregate_types}, {@code el_lease_fence_seq},
 * {@code el_progress}, {@code el_parked_events}) is one repeatable change. Each aggregate type's event and head tables
 * are a change of their own, known only once the aggregate type registers - the shard-owned queue's per-queue-sequence
 * pattern:
 * <ul>
 *     <li>when the harness' applier creates the schema, they are handed to the attached sink and recorded like any
 *     other change;</li>
 *     <li>when it does not (validate, emit, external), the engine creates them itself under the bootstrap lock as the
 *     aggregate type registers. <b>The database user then needs the right to create tables at runtime</b>, and a
 *     warning says so when the harness attaches.</li>
 * </ul>
 * Every statement is safe against existing objects; fillfactor is set at {@code CREATE} only.
 */
public final class EventLogSchemaContributor implements DynamicSchemaContributor {
    private static final Logger log = LoggerFactory.getLogger(EventLogSchemaContributor.class);

    /**
     * The {@link #moduleId()} the engine's schema is recorded under
     */
    public static final String MODULE_ID = "postgresql-event-log";

    private final DataSource                                 dataSource;
    private final ConcurrentNavigableMap<String, List<String>> aggregateTypes = new ConcurrentSkipListMap<>();
    private final AtomicReference<SchemaChangeSink>          sink           = new AtomicReference<>();
    private final AtomicBoolean                              warned         = new AtomicBoolean();

    /**
     * @param dataSource the engine's database: where an aggregate type's tables are created when the harness' applier
     *                   does not create the schema
     */
    public EventLogSchemaContributor(DataSource dataSource) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public int order() {
        return SchemaOrder.ORDER_EVENT_STORE;
    }

    @Override
    public List<SchemaChange> contribute(SchemaContext context) {
        var changes = new ArrayList<SchemaChange>(aggregateTypes.size() + 1);
        changes.add(SchemaChange.repeatable("engine-schema", EVENT_TYPES_TABLE, schemaStatements().toArray(String[]::new)));
        aggregateTypes.forEach((eventTable, statements) -> changes.add(aggregateTypeChange(eventTable, statements)));
        return changes;
    }

    @Override
    public void attach(SchemaChangeSink sink) {
        requireNonNull(sink, "No sink provided");
        this.sink.set(sink);
        if (!sink.createsSchema()) {
            if (warned.compareAndSet(false, true)) {
                log.warn("The schema harness is not creating the schema in this mode, but the event log creates each aggregate type's event and head " +
                                 "tables itself when the aggregate type registers. The database user this application connects as therefore needs the " +
                                 "right to create tables at runtime. The engine's fixed tables are covered by the harness as usual.");
            }
            for (var eventTable : new ArrayList<>(aggregateTypes.keySet())) {
                createDirectly(aggregateTypes.remove(eventTable));
            }
        }
    }

    /**
     * @return the executor to register aggregate types with when the harness owns the schema
     */
    public AggregateTypeDdlExecutor aggregateTypeDdl() {
        return (tables, statements) -> {
            var current = sink.get();
            if (current != null && !current.createsSchema()) {
                createDirectly(statements);
                return;
            }
            aggregateTypes.put(tables.eventTable(), List.copyOf(statements));
            if (current != null) {
                current.apply(List.of(aggregateTypeChange(tables.eventTable(), statements)));
            }
        };
    }

    private void createDirectly(List<String> statements) {
        try {
            lockedAggregateTypeDdl(dataSource).execute(null, statements);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create an aggregate type's event log tables - the database user needs the right to create tables", e);
        }
    }

    private static SchemaChange aggregateTypeChange(String eventTable, List<String> statements) {
        return SchemaChange.repeatable("aggregate-type", eventTable, statements.toArray(String[]::new));
    }

    /**
     * @return the fixed schema's statements, in order, each safe against existing objects (design §2.3 - §2.6)
     */
    public static List<String> schemaStatements() {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + EVENT_TYPES_TABLE + " (\n" +
                        "    id   smallint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,\n" +
                        "    name text     NOT NULL UNIQUE\n" +
                        ")",
                "CREATE TABLE IF NOT EXISTS " + AGGREGATE_TYPES_TABLE + " (\n" +
                        "    aggregate_type text        PRIMARY KEY,\n" +
                        "    event_table    text        NOT NULL UNIQUE,\n" +
                        "    head_table     text        NOT NULL UNIQUE,\n" +
                        "    created_at     timestamptz NOT NULL DEFAULT now()\n" +
                        ")",
                "CREATE SEQUENCE IF NOT EXISTS " + LEASE_FENCE_SEQUENCE,
                "CREATE TABLE IF NOT EXISTS " + PROGRESS_TABLE + " (\n" +
                        "    subscriber        text        NOT NULL,\n" +
                        "    aggregate_type    text        NOT NULL,\n" +
                        "    instance_name     text        NOT NULL,\n" +
                        "    exclusive         boolean     NOT NULL,\n" +
                        "    consistency_mode  text        NOT NULL CHECK (consistency_mode IN ('FRESH', 'CONTIGUOUS')),\n" +
                        "    progress_model    text        NOT NULL CHECK (progress_model IN ('PERIODIC', 'IN_HANDLER_TRANSACTION')),\n" +
                        "    resume_from       bigint      NOT NULL,\n" +
                        "    reset_epoch       bigint      NOT NULL DEFAULT 0,\n" +
                        "    lease_owner       text,\n" +
                        "    lease_until       timestamptz,\n" +
                        "    lease_fence       bigint,\n" +
                        "    created_at        timestamptz NOT NULL DEFAULT now(),\n" +
                        "    updated_at        timestamptz NOT NULL DEFAULT now(),\n" +
                        "    PRIMARY KEY (subscriber, aggregate_type, instance_name)\n" +
                        ") WITH (fillfactor = 70)",
                "CREATE TABLE IF NOT EXISTS " + PARKED_EVENTS_TABLE + " (\n" +
                        "    subscriber      text        NOT NULL,\n" +
                        "    aggregate_type  text        NOT NULL,\n" +
                        "    instance_name   text        NOT NULL,\n" +
                        "    global_order    bigint      NOT NULL,\n" +
                        "    aggregate_id    text        NOT NULL,\n" +
                        "    blocked_by      bigint,\n" +
                        "    attempts        integer     NOT NULL,\n" +
                        "    error_type      text,\n" +
                        "    error_message   text,\n" +
                        "    parked_at       timestamptz NOT NULL DEFAULT now(),\n" +
                        "    PRIMARY KEY (subscriber, aggregate_type, instance_name, global_order)\n" +
                        ")",
                createIndexUnlessPresent(PARKED_EVENTS_TABLE, PARKED_EVENTS_TABLE + "_aggregate",
                                         "CREATE INDEX IF NOT EXISTS " + PARKED_EVENTS_TABLE + "_aggregate ON " + PARKED_EVENTS_TABLE +
                                                 " (subscriber, aggregate_type, instance_name, aggregate_id, global_order)"));
    }

    /**
     * The opt-in {@code (event_type, global_order)} index (design phase 5 §5.1). Not a {@link SchemaChange}: the harness
     * applies a change set in one transaction, and {@code CONCURRENTLY} cannot run inside one, so {@code EventTypeIndex}
     * runs these in autocommit when the schema is created, and logs the create statement otherwise (§5.2).
     *
     * @return {@code [create, drop]}: the concurrent build, and the drop of an invalid index left by a failed build
     */
    public static List<String> eventTypeIndexStatements(AggregateTypeTables tables) {
        requireNonNull(tables, "No tables provided");
        var index = tables.eventTypeIndex();
        return List.of("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + index + " ON " + tables.eventTable() + " (event_type, global_order)",
                       "DROP INDEX CONCURRENTLY IF EXISTS " + index);
    }

    /**
     * @return whether the harness this contributor is attached to creates the schema (schema mode {@code create})
     */
    boolean createsSchema() {
        var current = sink.get();
        return current != null && current.createsSchema();
    }

    /**
     * @param tables the validated names of one aggregate type
     * @return its event table, head table and registry row, each statement safe against existing objects (design §2.1,
     * §2.2, §2.4). The registry insert does nothing on any conflict; the engine checks afterwards that the row maps back
     * to this aggregate type
     */
    public static List<String> aggregateTypeStatements(AggregateTypeTables tables) {
        requireNonNull(tables, "No tables provided");
        var events = tables.eventTable();
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + events + " (\n" +
                        "    global_order       bigint      GENERATED BY DEFAULT AS IDENTITY (CACHE 1) PRIMARY KEY,\n" +
                        "    event_order        bigint      NOT NULL,\n" +
                        "    timestamp          timestamptz NOT NULL,\n" +
                        "    event_id           uuid        NOT NULL,\n" +
                        "    caused_by_event_id uuid,\n" +
                        "    correlation_id     uuid,\n" +
                        "    event_type         smallint    NOT NULL,\n" +
                        "    event_revision     smallint    NOT NULL,\n" +
                        "    aggregate_id       text        NOT NULL,\n" +
                        "    event_payload      json        NOT NULL,\n" +
                        "    event_metadata     json        NOT NULL,\n" +
                        "    tenant             text,\n" +
                        "    CONSTRAINT " + tables.streamOrderConstraint() + " UNIQUE (aggregate_id, event_order)\n" +
                        ")",
                createIndexUnlessPresent(events, tables.eventIdIndex(),
                                         "CREATE INDEX IF NOT EXISTS " + tables.eventIdIndex() + " ON " + events + " (event_id)"),
                "CREATE TABLE IF NOT EXISTS " + tables.headTable() + " (\n" +
                        "    aggregate_id text   PRIMARY KEY,\n" +
                        "    version      bigint NOT NULL\n" +
                        ") WITH (fillfactor = 50)",
                "INSERT INTO " + AGGREGATE_TYPES_TABLE + " (aggregate_type, event_table, head_table) VALUES ('" +
                        tables.aggregateType().replace("'", "''") + "', '" + events + "', '" + tables.headTable() + "') ON CONFLICT DO NOTHING");
    }
}
