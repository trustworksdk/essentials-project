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

import dk.trustworks.essentials.components.eventlog.migration.ImportReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MigrationCliTest {
    @TempDir
    Path dir;

    private static ImportReport.SubscriptionImport imported(long safeReplayRows, boolean trusted) {
        return new ImportReport.SubscriptionImport("old", "Orders", "view", List.of(""), 200_000, trusted ? 200_000 : 90_000, trusted ? 0 : 110_000,
                                                   trusted ? 0 : safeReplayRows, 2, 1, 0, 90_000, safeReplayRows, trusted, trusted ? List.of(90_000L) : List.of(),
                                                   trusted ? 1 : 0);
    }

    @Test
    void the_preview_flags_a_large_safe_replay_with_a_hint_about_the_opt_in() {
        var large = imported(ImportReport.LARGE_REPLAY_ROWS + 1, false);
        assertThat(large.largeReplay()).isTrue();
        assertThat(MigrationCli.optInLine(large)).hasValueSatisfying(line -> assertThat(line).contains("LARGE REPLAY", "10001 rows", "trustOldResumePoint: true"));

        var small = imported(ImportReport.LARGE_REPLAY_ROWS, false);
        assertThat(small.largeReplay()).isFalse();
        assertThat(MigrationCli.optInLine(small)).isEmpty();

        var trusted = imported(ImportReport.LARGE_REPLAY_ROWS + 1, true);
        assertThat(trusted.largeReplay()).as("opted in: nothing to replay").isFalse();
        assertThat(MigrationCli.optInLine(trusted)).hasValueSatisfying(line -> assertThat(line).contains("WARNING: trustOldResumePoint", "safe I 90000", "[90000]"));
    }

    @Test
    void arguments_are_parsed_per_command() {
        var copy = MigrationCli.Arguments.parse("copy", "--config", "m.yml", "--until-caught-up", "--max-delta", "0", "--max-epochs", "3");
        assertThat(copy).isEqualTo(new MigrationCli.Arguments("copy", Path.of("m.yml"), true, 0, 3, false, false));

        var defaults = MigrationCli.Arguments.parse("copy", "--config=m.yml", "--until-caught-up");
        assertThat(defaults.maxDelta()).isEqualTo(MigrationCli.DEFAULT_MAX_DELTA);
        assertThat(defaults.maxEpochs()).isEqualTo(MigrationCli.DEFAULT_MAX_EPOCHS);

        assertThat(MigrationCli.Arguments.parse("verify", "--config", "m.yml", "--all").all()).isTrue();
        assertThat(MigrationCli.Arguments.parse("verify", "--config", "m.yml", "--after-cutover").afterCutover()).isTrue();
    }

    @Test
    void misuse_is_refused() {
        assertThatThrownBy(MigrationCli.Arguments::parse).hasMessageContaining("no command");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("migrate", "--config", "m.yml")).hasMessageContaining("unknown command 'migrate'");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("copy")).hasMessageContaining("--config");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("copy", "--config")).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("cutover", "--config", "m.yml", "--all")).hasMessageContaining("applies only to 'verify'");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("verify", "--config", "m.yml", "--until-caught-up")).hasMessageContaining("applies only to 'copy'");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("copy", "--config", "m.yml", "--max-delta", "5")).hasMessageContaining("only with --until-caught-up");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("verify", "--config", "m.yml", "--all", "--after-cutover")).hasMessageContaining("exclusive");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("copy", "--config", "m.yml", "--until-caught-up", "--max-epochs", "0")).hasMessageContaining("> 0");
        assertThatThrownBy(() -> MigrationCli.Arguments.parse("status", "--config", "m.yml", "--force")).hasMessageContaining("unknown option '--force'");
    }

    @Test
    void a_usage_or_configuration_error_exits_2_with_the_usage() throws IOException {
        var err = new ByteArrayOutputStream();
        var cli = new MigrationCli(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(cli.run("copy")).isEqualTo(MigrationCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--config <migration.yml> is required").contains("usage: MigrationCli");

        assertThat(cli.run("status", "--config", dir.resolve("missing.yml").toString())).isEqualTo(MigrationCli.USAGE);

        var config = Files.writeString(dir.resolve("m.yml"), "connection: {url: 'jdbc:postgresql://localhost/x'}\ntables: []\n");
        assertThat(cli.run("status", "--config", config.toString())).isEqualTo(MigrationCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("No tables provided");
    }
}
