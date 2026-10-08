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
package dk.trustworks.essentials.components.foundation.postgresql;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.schema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlEmitSchemaApplierTest {
    @TempDir
    Path tempDir;

    private ListAppender<ILoggingEvent>    logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlEmitSchemaApplier.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void the_log_line_lists_what_the_script_covers() {
        var emitter = new PostgresqlEmitSchemaApplier(tempDir.resolve("essentials-schema.sql"));

        emitter.apply(List.of(eventStreamTable("Orders", "orders_events"), eventStreamTable("Products", "products_events")));

        assertThat(logAppender.list).singleElement()
                                    .satisfies(event -> {
                                        assertThat(event.getLevel()).isEqualTo(Level.INFO);
                                        assertThat(event.getFormattedMessage()).startsWith("Wrote the schema of 2 change set(s), 2 change(s)")
                                                                               .endsWith("postgresql-event-store: orders_events (AggregateType 'Orders'); " +
                                                                                                 "postgresql-event-store: products_events (AggregateType 'Products')");
                                    });
    }

    @Test
    void a_registration_after_the_exit_was_triggered_is_appended_and_warned_about() throws Exception {
        var scriptFile = tempDir.resolve("essentials-schema.sql");
        var emitter    = new PostgresqlEmitSchemaApplier(scriptFile);
        emitter.apply(List.of(eventStreamTable("Orders", "orders_events")));
        emitter.apply(List.of(eventStreamTable("Products", "products_events")));
        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);

        emitter.exitTriggered();
        emitter.apply(List.of(eventStreamTable("Invoices", "invoices_events")));

        assertThat(emitter.isExitTriggered()).isTrue();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                                    .singleElement()
                                    .extracting(ILoggingEvent::getFormattedMessage).asString()
                                    .contains("invoices_events (AggregateType 'Invoices') registered after the application began stopping")
                                    .contains("re-run the emit mode");
        assertThat(Files.readString(scriptFile)).contains("-- event-stream-table on invoices_events (repeatable) - AggregateType 'Invoices'");
    }

    private static SchemaChangeSet eventStreamTable(String aggregateType, String tableName) {
        return new SchemaChangeSet("postgresql-event-store",
                                   SchemaOrder.ORDER_EVENT_STORE,
                                   List.of(SchemaChange.repeatable("event-stream-table", tableName, "CREATE TABLE IF NOT EXISTS " + tableName + " (id BIGINT)")
                                                       .describedAs("AggregateType '" + aggregateType + "'")));
    }
}
