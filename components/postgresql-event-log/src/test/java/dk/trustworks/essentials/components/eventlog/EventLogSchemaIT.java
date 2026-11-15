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
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

class EventLogSchemaIT extends AbstractEventLogIT {

    @Test
    void component_owned_schema_and_aggregate_type_creation_run_twice_without_error() throws Exception {
        for (var i = 0; i < 2; i++) {
            var eventLog = startedEventLog("Orders");
            eventLog.registerAggregateType("Orders");
            EventLogSchema.initialize(dataSource);
            EventLogSchema.lockedAggregateTypeDdl(dataSource).execute(null, EventLogSchemaContributor.aggregateTypeStatements(AggregateTypeTables.of("Orders")));
        }
        assertThat(queryLong("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tablename IN " +
                                     "('el_event_types', 'el_aggregate_types', 'el_progress', 'el_parked_events', 'el_orders_events', 'el_orders_heads')")).isEqualTo(6);
        assertThat(queryLong("SELECT count(*) FROM pg_sequences WHERE sequencename = 'el_lease_fence_seq'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM pg_indexes WHERE indexname IN ('el_orders_events_event_id', 'el_parked_events_aggregate')")).isEqualTo(2);
        assertThat(queryLong("SELECT count(*) FROM pg_constraint WHERE conname = 'el_orders_events_stream_order'")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM el_aggregate_types WHERE aggregate_type = 'Orders'")).isEqualTo(1);
    }

    @Test
    void harness_owned_schema_runs_every_change_twice_and_the_engine_executes_no_ddl_itself() throws Exception {
        var contributor = new EventLogSchemaContributor(dataSource);
        var applied     = new AtomicInteger();
        SchemaApplier applier = new SchemaApplier() {
            @Override
            public void apply(List<SchemaChangeSet> changeSets) {
                for (var set : changeSets) {
                    for (var change : set.changes()) {
                        for (var sql : change.statements()) {
                            try {
                                execute(sql);
                            } catch (SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        applied.incrementAndGet();
                    }
                }
            }

            @Override
            public boolean createsSchema() {
                return true;
            }
        };
        var eventLog = EventLog.builder().setDataSource(dataSource).setSchemaOwnership(SchemaOwnership.HARNESS).setSchemaContributor(contributor).build();
        eventLog.start();
        assertThat(queryLong("SELECT count(*) FROM pg_tables WHERE tablename = 'el_event_types'")).as("HARNESS: start runs no DDL").isZero();

        // Registered before the harness ran: recorded, created by the harness sweep
        eventLog.registerAggregateType("Orders");
        assertThat(queryLong("SELECT count(*) FROM pg_tables WHERE tablename = 'el_orders_events'")).isZero();
        for (var i = 0; i < 2; i++) {
            var harness = new EssentialsSchemaHarness(applier, SchemaContext.empty(), List.of(contributor));
            harness.apply();
        }
        contributor.attach(applier.sinkFor(contributor));
        // Registered after: handed to the sink
        eventLog.registerAggregateType("Customers");
        assertThat(applied.get()).isEqualTo(5);
        assertThat(contributor.contribute(SchemaContext.empty())).extracting(SchemaChange::objectName)
                                                                 .containsExactly("el_event_types", "el_customers_events", "el_orders_events");

        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 1)),
                        AppendRequest.afterEventOrder("Customers", "c1", -1, events("E", 1)));
        assertThat(eventLog.loadStream("Customers", "c1")).hasSize(1);
    }

    @Test
    void an_identity_cache_above_one_is_refused() throws Exception {
        startedEventLog("Orders");
        execute("ALTER TABLE el_orders_events ALTER COLUMN global_order SET CACHE 20");
        var restarted = startedEventLog();
        assertThatThrownBy(() -> restarted.registerAggregateType("Orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CACHE 20")
                .hasMessageContaining("requires CACHE 1");
    }

    @Test
    void two_aggregate_types_mapping_to_the_same_tables_are_refused() throws Exception {
        var eventLog = startedEventLog("Orders");
        assertThatThrownBy(() -> eventLog.registerAggregateType("ORDERS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already belongs to aggregate type 'Orders'");
    }

    /**
     * Stock PostgreSQL shows {@code backend_xid} of every backend to every role, so the refusal cannot be provoked with
     * roles. A managed service that redacts it is simulated by an observer whose search path resolves
     * {@code pg_stat_get_activity} to a function returning the column null.
     */
    @Test
    void start_refuses_a_database_that_hides_another_backends_xid() throws Exception {
        execute("CREATE SCHEMA el_redacted");
        execute("CREATE FUNCTION el_redacted.pg_stat_get_activity(integer) RETURNS TABLE(pid integer, backend_xid xid) LANGUAGE sql " +
                        "AS $$ SELECT a.pid, NULL::xid FROM pg_catalog.pg_stat_get_activity($1) a $$");
        var redacting = dataSource("");
        redacting.setOptions("-c search_path=el_redacted,pg_catalog,public");
        try {
            assertThatThrownBy(() -> EventLogSchema.verifyWatermarkPrerequisites(new AlternatingDataSource(dataSource, redacting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backend_xid was not visible")
                .hasMessageContaining("pg_read_all_stats");
        assertThatThrownBy(() -> EventLog.builder().setDataSource(redacting).build().start())
                .isInstanceOf(IllegalStateException.class);

        } finally {
            execute("DROP SCHEMA el_redacted CASCADE");
        }
        EventLogSchema.verifyWatermarkPrerequisites(new AlternatingDataSource(dataSource, dataSource("")));
    }

    /** Hands out the first data source's connection, then the second's, alternately */
    private record AlternatingDataSource(DataSource first, DataSource second, AtomicInteger calls) implements DataSource {
        AlternatingDataSource(DataSource first, DataSource second) {
            this(first, second, new AtomicInteger());
        }

        @Override
        public Connection getConnection() throws SQLException {
            return (calls.getAndIncrement() % 2 == 0 ? first : second).getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
