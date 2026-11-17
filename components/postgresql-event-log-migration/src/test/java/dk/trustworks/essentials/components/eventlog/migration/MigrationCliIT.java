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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.AggregateTypeTables;
import dk.trustworks.essentials.components.eventlog.migration.cli.MigrationCli;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static dk.trustworks.essentials.components.eventlog.migration.AbstractMigrationIT.I8.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit 9-F: the procedure of design phase 9 §5, run through {@link MigrationCli} with a YAML configuration against the I8
 * fixture ({@link #i8Source()}), from pre-flight to the check for late rows after the cut-over
 */
class MigrationCliIT extends AbstractMigrationIT {
    @TempDir
    Path dir;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private MigrationCli          cli;

    @BeforeEach
    void cli() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        cli = new MigrationCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void the_procedure_runs_end_to_end_through_the_cli() throws Exception {
        var old    = i8Source();
        var config = config(old.table(ORDERS), old.table(PAYMENTS));

        assertThat(run("status", config)).isZero();
        assertThat(output()).contains("Orders <- " + old.table(ORDERS) + ": not prepared");

        assertThat(run("preflight", config)).isZero();
        assertThat(output()).contains("Pre-flight PASSED").contains("Orders <- " + old.table(ORDERS));
        assertThat(queryLong("SELECT count(*) FROM el_migration")).as("pre-flight writes nothing").isZero();

        assertThat(run("prepare", config)).isZero();
        assertThat(run("copy", config)).isZero();
        assertThat(output()).contains("Epoch 1: 19 rows").contains("Orders: global_order (0, 16], 14 rows");

        // A sequence jump, and events above it the old subscriber never saw
        execute("SELECT setval(pg_get_serial_sequence('" + old.table(ORDERS) + "', 'global_order'), 1000)");
        old.stream(ORDERS, 3);                                                      // 1001..1003
        assertThat(run("copy", config, "--until-caught-up", "--max-delta", "0")).isZero();
        assertThat(output()).contains("Epoch 1: 3 rows").contains("Epoch 2: 0 rows").contains("Caught up: epoch 2");

        assertThat(run("verify", config)).isZero();
        assertThat(output()).contains("EQUAL");
        assertThat(run("verify", config, "--all")).isZero();
        assertThat(output()).contains("Verification (ONLINE) EQUAL").contains("Orders: global_order (0, 1003], 17 rows");

        // The import preview, while the old engine still runs: R, I and the replay per mapping, rolled back
        var oldSubscriptions = queryLong("SELECT count(*) + sum(resume_from_and_including_global_eventorder) FROM durable_subscriptions") +
                               queryLong("SELECT count(*) FROM transient_subscriber_gaps") + queryLong("SELECT count(*) FROM permanent_gaps");
        assertThat(run("import-preview", config)).isZero();
        assertThat(output()).contains("Import preview (rolled back")
                            .contains("orders-projector -> orders-view (Orders): R " + R + ", I " + GAP_FILL + ", replay 4 rows (" + (R - GAP_FILL) + " values)")
                            .contains("orders-projector -> orders-mailer (Orders, instances [node-a, node-b]): R " + R + ", I " + GAP_FILL + ", replay 4 rows")
                            .contains("WARNING: old subscriber " + OLD_LEGACY + " (" + PAYMENTS + ", resume from 2) is not mapped");
        assertThat(queryLong("SELECT count(*) FROM el_progress")).as("the preview rolled back").isZero();
        assertThat(queryLong("SELECT count(*) + sum(resume_from_and_including_global_eventorder) FROM durable_subscriptions") +
                   queryLong("SELECT count(*) FROM transient_subscriber_gaps") + queryLong("SELECT count(*) FROM permanent_gaps"))
                .as("the old subscription and gap tables are untouched").isEqualTo(oldSubscriptions);

        // Every old-engine node stopped; the cut-over with the resume-point import
        assertThat(run("cutover", config)).isZero();
        assertThat(output()).contains("Cut over")
                            .contains("orders-projector -> orders-view (Orders): resume from " + GAP_FILL + " (old " + R + ")")
                            .contains("orders-projector -> orders-mailer (Orders, instances [node-a, node-b]): resume from " + GAP_FILL)
                            .contains("WARNING: old subscriber " + OLD_LEGACY);
        assertThat(longs("SELECT resume_from FROM el_progress ORDER BY subscriber, instance_name")).containsExactly(GAP_FILL, GAP_FILL, GAP_FILL);
        for (var type : new String[]{ORDERS, PAYMENTS}) {
            assertThat(differingRows(old, type, oldMax(old, type))).as("rows of %s that differ", type).isZero();
            assertThat(newCount(type)).isEqualTo(queryLong("SELECT count(*) FROM " + old.table(type)));
        }
        assertThat(queryLong("SELECT max(global_order) FROM " + AggregateTypeTables.of(ORDERS).eventTable())).isEqualTo(1003);

        assertThat(run("status", config)).isZero();
        assertThat(output()).contains("Orders <- " + old.table(ORDERS) + ": CUT_OVER, copied through 1003");
        assertThat(run("revoke-statements", config)).isZero();
        assertThat(output()).contains("REVOKE INSERT ON " + old.table(ORDERS) + " FROM <old application role>;");

        assertThat(run("verify-after-cutover", config)).isZero();
        assertThat(output()).contains("no late rows");

        // A forgotten old node appends: reported, nonzero exit
        old.stream(ORDERS, 1);
        assertThat(run("verify", config, "--after-cutover")).isEqualTo(MigrationCli.FAILED);
        assertThat(output()).contains("LATE ROWS").contains("Orders <- " + old.table(ORDERS) + ": copied through 1003, 1 late rows");

        // A second cut-over is refused
        assertThat(run("cutover", config)).isEqualTo(MigrationCli.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("REFUSED");
    }

    @Test
    void a_refused_preflight_and_an_unprepared_copy_exit_nonzero() throws Exception {
        var old    = i8Source();
        var config = config(old.table(ORDERS), "no_such_table");

        assertThat(run("preflight", config)).isEqualTo(MigrationCli.FAILED);
        assertThat(output()).contains("Pre-flight REFUSED").contains("SOURCE_TABLE_MISSING");
        assertThat(run("prepare", config)).isEqualTo(MigrationCli.FAILED);
        assertThat(output()).contains("nothing was written");
        assertThat(queryLong("SELECT count(*) FROM el_migration")).isZero();

        var valid = config(old.table(ORDERS), old.table(PAYMENTS));
        assertThat(run("copy", valid)).isEqualTo(MigrationCli.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("REFUSED");
    }

    // ------------------------------------------------------------------ helpers

    private int run(String command, Path config, String... options) {
        out.reset();
        var args = new String[options.length + 3];
        args[0] = command;
        args[1] = "--config";
        args[2] = config.toString();
        System.arraycopy(options, 0, args, 3, options.length);
        var exit = cli.run(args);
        System.out.println("$ " + String.join(" ", args) + " -> " + exit + "\n" + output() + err.toString(StandardCharsets.UTF_8));
        return exit;
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path config(String ordersTable, String paymentsTable) throws IOException {
        return Files.writeString(dir.resolve("migration-" + paymentsTable + ".yml"), """
                connection:
                  url: %s
                  user: %s
                  password: %s
                tables:
                  - aggregateType: %s
                    table: %s
                  - aggregateType: %s
                    table: %s
                settings:
                  batchSize: 4
                  epochWaitTimeout: 30s
                import:
                  subscriptions:
                    - oldSubscriber: %s
                      subscriber: orders-view
                      aggregateType: %s
                      model: PERIODIC
                    - oldSubscriber: %s
                      subscriber: orders-mailer
                      aggregateType: %s
                      model: IN_HANDLER_TRANSACTION
                      consistency: CONTIGUOUS
                      instances: [node-a, node-b]
                """.formatted(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), ORDERS, ordersTable, PAYMENTS, paymentsTable, OLD_ORDERS, ORDERS, OLD_ORDERS,
                              ORDERS));
    }
}
