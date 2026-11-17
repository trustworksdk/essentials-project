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

package dk.trustworks.essentials.components.eventlog.migration.cli;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.foundation.schema.SchemaOwnership;
import dk.trustworks.essentials.components.eventlog.migration.*;

import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The migration's command line (design phase 9 §5): one subcommand per step of the procedure, each reading the same YAML
 * configuration ({@link MigrationConfigYaml}) and printing a plain-text report.
 * <pre>
 * java -cp postgresql-event-log-migration.jar:postgresql-event-log.jar:foundation.jar:shared.jar:...:postgresql.jar:snakeyaml.jar \
 *      dk.trustworks.essentials.components.eventlog.migration.cli.MigrationCli &lt;command&gt; --config migration.yml [options]
 *
 *   preflight                          read only: column types, sequences and refusals
 *   prepare                            the event log's schema, its tables per aggregate type, el_migration rows COPYING
 *   copy [--until-caught-up]           one online epoch; or epochs until one copies at most --max-delta rows
 *        [--max-delta N] [--max-epochs N]   (defaults 5000 and 100)
 *   verify [--all] [--after-cutover]   the copied ranges not verified yet; --all every copied row; --after-cutover late rows
 *   import-preview                     read only, no locks: per mapping R, I and the replay, and the unmapped old subscribers
 *   cutover                            the final pass, with every old-engine node stopped, plus the resume-point import
 *   verify-after-cutover               as verify --after-cutover
 *   status                             each aggregate type's el_migration row
 *   revoke-statements                  the REVOKE statements an operator may run after the cut-over
 * </pre>
 * Exit codes: {@value #OK} success; {@value #FAILED} refused, timed out or not equal (the report says which);
 * {@value #USAGE} a usage or configuration error; {@value #ERROR} an unexpected error (e.g. the database is unreachable).
 * <p>
 * The CLI needs SnakeYAML, the pgjdbc driver and an SLF4J binding on the classpath; the module declares them
 * {@code provided}, so that its {@link Migration} API stays usable without them.
 */
public final class MigrationCli {
    public static final int OK     = 0;
    public static final int FAILED = 1;
    public static final int USAGE  = 2;
    public static final int ERROR  = 3;

    static final long DEFAULT_MAX_DELTA  = 5_000;
    static final int  DEFAULT_MAX_EPOCHS = 100;

    private static final Set<String> COMMANDS = Set.of("preflight", "prepare", "copy", "verify", "import-preview", "cutover", "verify-after-cutover", "status", "revoke-statements");

    private final PrintStream out;
    private final PrintStream err;

    public MigrationCli(PrintStream out, PrintStream err) {
        this.out = requireNonNull(out, "No out provided");
        this.err = requireNonNull(err, "No err provided");
    }

    public static void main(String[] args) {
        System.exit(new MigrationCli(System.out, System.err).run(args));
    }

    /**
     * @return the exit code
     */
    public int run(String... args) {
        Arguments arguments;
        MigrationConfig config;
        try {
            arguments = Arguments.parse(args);
            config = MigrationConfigYaml.read(arguments.config);
        } catch (Exception e) {
            err.println("error: " + e.getMessage());
            err.println(usage());
            return USAGE;
        }
        try {
            return execute(arguments, config);
        } catch (PreflightRefusedException e) {
            out.println("Pre-flight REFUSED; nothing was written:");
            out.print(e.report().describe());
            return FAILED;
        } catch (VerificationFailedException e) {
            out.println("Verification FAILED; the cut-over rolled back:");
            out.print(e.report().describe());
            return FAILED;
        } catch (CutoverLockTimeoutException | IllegalStateException e) {
            err.println("REFUSED: " + e.getMessage());
            return FAILED;
        } catch (Exception e) {
            err.println("error: " + e);
            return ERROR;
        }
    }

    private int execute(Arguments arguments, MigrationConfig config) throws Exception {
        var dataSource = config.dataSource();
        // The import preview needs a started event log only for EventLogAdmin; HARNESS ownership keeps the start from running DDL
        var eventLog = arguments.command.equals("import-preview")
                       ? EventLog.builder().setDataSource(dataSource).setSchemaOwnership(SchemaOwnership.HARNESS)
                               .setSchemaContributor(new EventLogSchemaContributor(dataSource)).build()
                       : config.eventLog(dataSource);
        // preflight, verify and status are read only, and starting the event log may create its schema (COMPONENT)
        var start = Set.of("prepare", "copy", "import-preview", "cutover").contains(arguments.command);
        if (start) {
            eventLog.start();
        }
        try {
            var importStep = arguments.command.equals("cutover") ? config.resumePointImport(eventLog) : Optional.<ResumePointImport>empty();
            var migration  = new Migration(config.plan(dataSource, eventLog, importStep.<List<CutoverStep>>map(List::of).orElse(List.of())));
            return switch (arguments.command) {
                case "preflight" -> preflight(migration);
                case "prepare" -> prepare(migration);
                case "copy" -> copy(migration, arguments);
                case "verify" -> arguments.afterCutover ? verifyAfterCutover(migration) : verify(migration, arguments.all);
                case "import-preview" -> importPreview(config, eventLog, dataSource);
                case "cutover" -> cutover(migration, importStep);
                case "verify-after-cutover" -> verifyAfterCutover(migration);
                case "status" -> status(migration, config);
                case "revoke-statements" -> {
                    migration.revokeStatements().forEach(s -> out.println(s + ";"));
                    yield OK;
                }
                default -> throw new IllegalStateException("Unknown command " + arguments.command);
            };
        } finally {
            if (start) {
                eventLog.stop();
            }
        }
    }

    // ------------------------------------------------------------------ the commands

    private int preflight(Migration migration) throws Exception {
        var report = migration.preflight();
        out.println("Pre-flight " + (report.passed() ? "PASSED" : "REFUSED") + ":");
        out.print(report.describe());
        return report.passed() ? OK : FAILED;
    }

    private int prepare(Migration migration) throws Exception {
        var report = migration.prepare();
        out.println("Pre-flight PASSED:");
        out.print(report.describe());
        out.println("Prepared: every aggregate type is registered with the event log and COPYING");
        return OK;
    }

    private int copy(Migration migration, Arguments arguments) throws Exception {
        for (var epoch = 1; epoch <= (arguments.untilCaughtUp ? arguments.maxEpochs : 1); epoch++) {
            var report = migration.copyEpoch();
            print(epoch, report);
            if (report.timedOut()) {
                out.println("Epoch " + epoch + " TIMED OUT waiting for older transactions; nothing was copied. Blockers:");
                report.blockers().forEach(b -> out.println("  pid " + b.pid() + " (" + b.applicationName() + "), transaction started " + b.transactionStart() +
                                                                   ", " + b.state()));
                return FAILED;
            }
            if (!arguments.untilCaughtUp) {
                return OK;
            }
            if (report.rows() <= arguments.maxDelta) {
                out.println("Caught up: epoch " + epoch + " copied " + report.rows() + " rows (at most " + arguments.maxDelta + ")");
                return OK;
            }
        }
        out.println("NOT caught up after " + arguments.maxEpochs + " epochs (more than " + arguments.maxDelta + " rows per epoch)");
        return FAILED;
    }

    private void print(int epoch, EpochReport report) {
        out.println("Epoch " + epoch + ": " + report.rows() + " rows, waited " + report.waited() + ", copied in " + report.copyTime());
        report.types().forEach(t -> out.println("  " + t.aggregateType() + ": global_order (" + t.from() + ", " + t.to() + "], " + t.rows() + " rows"));
    }

    private int verify(Migration migration, boolean all) throws Exception {
        var report = all ? migration.verifyAll() : migration.verify();
        out.println("Verification (" + report.mode() + ") " + (report.equal() ? "EQUAL" : "DIFFERS") + ", took " + report.took() + ":");
        out.print(report.describe());
        return report.equal() ? OK : FAILED;
    }

    private int importPreview(MigrationConfig config, EventLog eventLog, javax.sql.DataSource dataSource) throws Exception {
        var types = config.tables().stream().map(SourceTable::aggregateType).toList();
        ImportReport report;
        try (var connection = dataSource.getConnection()) {
            report = config.importStep(eventLog).preview(connection, types);
        }
        out.println("Import preview (read only, no locks; as of the last copy epoch, the cut-over may import a lower I and replay more):");
        for (var i : report.imported()) {
            out.println("  " + i.oldSubscriberId() + " -> " + i.subscriber() + " (" + i.aggregateType() + (i.instanceNames().equals(List.of("")) ? "" : ", instances " +
                    i.instanceNames()) + "): R " + i.oldResumeFrom() + ", I " + i.resumeFrom() + ", replay " + i.replayRows() + " rows (" + i.replayValues() +
                                " values); " + i.openTransientGaps() + " open transient gaps, " + i.filledOpenTransientGaps() + " filled");
        }
        if (report.imported().isEmpty()) {
            out.println("  no subscription mapped");
        }
        if (!report.gapTracking()) {
            out.println("  the old engine tracked no gaps: every resume point would be imported unchanged");
        }
        for (var u : report.unmapped()) {
            out.println("  WARNING: old subscriber " + u.subscriberId() + " (" + u.aggregateType() + ", resume from " + u.resumeFrom() + ") is not mapped");
        }
        return OK;
    }

    private int cutover(Migration migration, Optional<ResumePointImport> importStep) throws Exception {
        var report = migration.cutover();
        out.println("Cut over, waited " + report.lockWait() + " for the locks, stop " + report.stop() + ":");
        report.types().forEach(t -> out.println("  " + t.aggregateType() + ": global_order (" + t.from() + ", " + t.to() + "], " + t.rows() + " rows, identity sequence " +
                                                        t.sequenceValue()));
        importStep.flatMap(ResumePointImport::report).ifPresent(this::print);
        return OK;
    }

    private void print(ImportReport report) {
        out.println("Imported resume points" + (report.gapTracking() ? "" : " (the old engine tracked no gaps: late commits it skipped cannot be identified)") + ":");
        for (var i : report.imported()) {
            out.println("  " + i.oldSubscriberId() + " -> " + i.subscriber() + " (" + i.aggregateType() + (i.instanceNames().equals(List.of("")) ? "" : ", instances " +
                    i.instanceNames()) + "): resume from " + i.resumeFrom() + " (old " + i.oldResumeFrom() + "), replays " + i.replayRows() + " rows; " +
                                i.openTransientGaps() + " open transient gaps, " + i.filledOpenTransientGaps() + " filled; " + i.lateFilledPermanentGapsBelowResume() +
                                " permanent gaps below the resume point filled late (not delivered by the old engine either)");
        }
        for (var u : report.unmapped()) {
            out.println("  WARNING: old subscriber " + u.subscriberId() + " (" + u.aggregateType() + ", resume from " + u.resumeFrom() + ") was not imported");
        }
    }

    private int verifyAfterCutover(Migration migration) throws Exception {
        var report = migration.verifyAfterCutover();
        out.println("After the cut-over: " + (report.clean() ? "no late rows" : "LATE ROWS in the old tables, written by an old-engine node that should have been stopped"));
        for (var t : report.types()) {
            out.println("  " + t.aggregateType() + " <- " + t.oldTable() + ": copied through " + t.copiedThrough() + ", " + t.count() + " late rows" +
                                (t.count() > 0 ? " (up to global_order " + t.max() + ", first " + t.samples() + ")" : ""));
        }
        return report.clean() ? OK : FAILED;
    }

    private int status(Migration migration, MigrationConfig config) throws Exception {
        for (var table : config.tables()) {
            var status = migration.status(table.aggregateType());
            out.println("  " + table.aggregateType() + " <- " + table.tableName() + ": " +
                                status.map(s -> s.state() + ", copied through " + s.copiedThrough() + " in " + s.epochs() + " epochs, started " + s.startedAt() +
                                        (s.cutOverAt() == null ? "" : ", cut over " + s.cutOverAt()))
                                      .orElse("not prepared"));
        }
        return OK;
    }

    // ------------------------------------------------------------------ arguments

    static String usage() {
        return """
               usage: MigrationCli <command> --config <migration.yml> [options]
                 preflight
                 prepare
                 copy [--until-caught-up [--max-delta N] [--max-epochs N]]
                 verify [--all | --after-cutover]
                 import-preview
                 cutover
                 verify-after-cutover
                 status
                 revoke-statements""";
    }

    record Arguments(String command, Path config, boolean untilCaughtUp, long maxDelta, int maxEpochs, boolean all, boolean afterCutover) {
        static Arguments parse(String... args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("no command given");
            }
            var command = args[0];
            if (!COMMANDS.contains(command)) {
                throw new IllegalArgumentException("unknown command '" + command + "'");
            }
            Path    config        = null;
            boolean untilCaughtUp = false, all = false, afterCutover = false;
            Long    maxDelta      = null;
            Integer maxEpochs     = null;
            for (var i = 1; i < args.length; i++) {
                var arg = args[i];
                switch (arg) {
                    case "--config" -> config = Path.of(value(args, ++i, arg));
                    case "--until-caught-up" -> untilCaughtUp = only(command, "copy", arg);
                    case "--max-delta" -> maxDelta = nonNegative(Long.parseLong(value(args, ++i, arg)), arg);
                    case "--max-epochs" -> maxEpochs = (int) positive(Integer.parseInt(value(args, ++i, arg)), arg);
                    case "--all" -> all = only(command, "verify", arg);
                    case "--after-cutover" -> afterCutover = only(command, "verify", arg);
                    default -> {
                        if (arg.startsWith("--config=")) {
                            config = Path.of(arg.substring("--config=".length()));
                        } else {
                            throw new IllegalArgumentException("unknown option '" + arg + "'");
                        }
                    }
                }
            }
            if (config == null) {
                throw new IllegalArgumentException("--config <migration.yml> is required");
            }
            if (all && afterCutover) {
                throw new IllegalArgumentException("--all and --after-cutover are exclusive");
            }
            if ((maxDelta != null || maxEpochs != null) && !untilCaughtUp) {
                throw new IllegalArgumentException("--max-delta and --max-epochs apply only with --until-caught-up");
            }
            return new Arguments(command, config, untilCaughtUp, maxDelta == null ? DEFAULT_MAX_DELTA : maxDelta, maxEpochs == null ? DEFAULT_MAX_EPOCHS : maxEpochs, all,
                                 afterCutover);
        }

        private static boolean only(String command, String allowed, String option) {
            if (!command.equals(allowed)) {
                throw new IllegalArgumentException(option + " applies only to '" + allowed + "'");
            }
            return true;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }

        private static long nonNegative(long value, String option) {
            if (value < 0) {
                throw new IllegalArgumentException(option + " must be >= 0");
            }
            return value;
        }

        private static long positive(long value, String option) {
            if (value <= 0) {
                throw new IllegalArgumentException(option + " must be > 0");
            }
            return value;
        }
    }
}
