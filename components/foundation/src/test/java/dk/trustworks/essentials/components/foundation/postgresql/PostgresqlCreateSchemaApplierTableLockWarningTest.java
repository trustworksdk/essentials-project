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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The WARN set is JVM-wide, so every test here uses module ids of its own.
 */
class PostgresqlCreateSchemaApplierTableLockWarningTest {

    @Test
    void a_repeatable_change_with_a_bare_index_or_column_statement_is_warned_about_once_per_module_change_and_object() {
        var changeSets = List.of(new SchemaChangeSet("lock-warning-a", SchemaOrder.ORDER_APPLICATION, List.of(
                                         SchemaChange.repeatable("orders-table", "orders", "CREATE TABLE IF NOT EXISTS orders (id BIGINT, status TEXT)"),
                                         SchemaChange.repeatable("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"),
                                         SchemaChange.repeatable("orders-total-column", "orders", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS total NUMERIC"))),
                                 new SchemaChangeSet("lock-warning-b", SchemaOrder.ORDER_APPLICATION, List.of(
                                         SchemaChange.repeatable("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"))));

        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets))
                .containsExactly("lock-warning-a/orders-status-index/orders", "lock-warning-a/orders-total-column/orders", "lock-warning-b/orders-status-index/orders");
        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).as("each is warned about once").isEmpty();
    }

    @Test
    void changes_sharing_one_change_id_on_different_tables_are_each_warned_about() {
        // The shape of a contributor that emits one change id per table, as the durable queues' queue-indexes does
        var changeSets = List.of(new SchemaChangeSet("lock-warning-d", SchemaOrder.ORDER_APPLICATION, List.of(
                SchemaChange.repeatable("queue-indexes", "orders_queue", "CREATE INDEX IF NOT EXISTS orders_queue_ready_idx ON orders_queue (ready)"),
                SchemaChange.repeatable("queue-indexes", "invoices_queue", "CREATE INDEX IF NOT EXISTS invoices_queue_ready_idx ON invoices_queue (ready)"))));

        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets))
                .containsExactly("lock-warning-d/queue-indexes/orders_queue", "lock-warning-d/queue-indexes/invoices_queue");
        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).as("each is warned about once").isEmpty();
    }

    @Test
    void a_repeatable_change_with_a_bare_trigger_statement_is_warned_about() {
        var changeSets = List.of(new SchemaChangeSet("lock-warning-e", SchemaOrder.ORDER_APPLICATION, List.of(
                SchemaChange.repeatable("orders-trigger", "orders",
                                        "DROP TRIGGER IF EXISTS orders_changed ON orders",
                                        "CREATE TRIGGER orders_changed AFTER INSERT ON orders FOR EACH ROW EXECUTE FUNCTION notify_orders()"),
                SchemaChange.repeatable("orders-notify-trigger", "orders",
                                        ListenNotify.changeNotificationTriggerStatements("orders", List.of(ListenNotify.SqlOperation.INSERT)).toArray(String[]::new)))));

        var logger      = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PostgresqlCreateSchemaApplier.class);
        var logAppender = new ListAppender<ILoggingEvent>();
        logAppender.start();
        logger.addAppender(logAppender);
        try {
            assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).containsExactly("lock-warning-e/orders-trigger/orders");
        } finally {
            logger.detachAppender(logAppender);
        }

        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                                    .singleElement()
                                    .extracting(ILoggingEvent::getFormattedMessage).asString()
                                    .startsWith("[lock-warning-e] Repeatable schema change 'orders-trigger' on 'orders' creates or replaces a trigger without checking the catalog first")
                                    .contains("PostgreSQL takes SHARE ROW EXCLUSIVE (ACCESS EXCLUSIVE for a DROP TRIGGER that removes it first) on the statement's table even when the trigger already exists")
                                    .contains("PostgresqlSchemaStatements has no wrapper for a trigger")
                                    .endsWith("Statement: CREATE TRIGGER orders_changed AFTER INSERT ON orders FOR EACH ROW EXECUTE FUNCTION notify_orders()");
    }

    @Test
    void a_one_shot_change_or_a_wrapped_statement_is_not_warned_about() {
        var changeSets = List.of(new SchemaChangeSet("lock-warning-c", SchemaOrder.ORDER_APPLICATION, List.of(
                SchemaChange.once("orders-status-index", "orders", "CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status)"),
                SchemaChange.repeatable("orders-id-index", "orders",
                                        PostgresqlSchemaStatements.createIndexUnlessPresent("orders", "orders_id_idx", "CREATE INDEX IF NOT EXISTS orders_id_idx ON orders (id)")),
                SchemaChange.repeatable("orders-total-column", "orders",
                                        PostgresqlSchemaStatements.addColumnUnlessPresent("orders", "total", "ALTER TABLE orders ADD COLUMN IF NOT EXISTS total NUMERIC")))));

        assertThat(PostgresqlCreateSchemaApplier.warnOfRepeatableChangesLockingTheirTable(changeSets)).isEmpty();
    }
}
