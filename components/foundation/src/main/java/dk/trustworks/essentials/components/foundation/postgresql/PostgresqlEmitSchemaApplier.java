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

import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.shared.network.Network;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@code emit} mode: executes nothing, writes the complete schema as one script (see
 * {@link PostgresqlSchemaScript}), and then validates with {@link PostgresqlValidateSchemaApplier} - so startup fails
 * until someone with DDL rights has run the script, and passes once they have.
 * <p>
 * Emit once against any environment, hand the script over, and run {@code validate} from then on. Objects a
 * {@link DynamicSchemaContributor} registers later are appended to the same file as blocks of their own.
 */
public final class PostgresqlEmitSchemaApplier implements SchemaApplier {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlEmitSchemaApplier.class);

    private final Path                            scriptFile;
    private final PostgresqlSchemaScript          script;
    private final PostgresqlValidateSchemaApplier validator;
    private final AtomicBoolean                   written = new AtomicBoolean();

    /**
     * Uses {@link PostgresqlCreateSchemaApplier#DEFAULT_SCHEMA_HISTORY_TABLE_NAME}.
     *
     * @param scriptFile where the script is written - replaced on the first {@link #apply}
     * @param jdbi       the database to validate against afterwards
     */
    public PostgresqlEmitSchemaApplier(Path scriptFile, Jdbi jdbi) {
        this(scriptFile, new PostgresqlValidateSchemaApplier(jdbi));
    }

    /**
     * @param scriptFile where the script is written - replaced on the first {@link #apply}
     * @param validator  what the database is validated with afterwards; its ledger table is the one the script records into
     */
    public PostgresqlEmitSchemaApplier(Path scriptFile, PostgresqlValidateSchemaApplier validator) {
        this.scriptFile = requireNonNull(scriptFile, "No scriptFile provided");
        this.validator = requireNonNull(validator, "No validator provided");
        this.script = new PostgresqlSchemaScript(validator.getSchemaHistoryTableName());
    }

    public Path getScriptFile() {
        return scriptFile;
    }

    /**
     * @throws SchemaValidationException if the database does not have the schema yet - the expected outcome until the
     *                                   written script has been run
     */
    @Override
    public void apply(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        var first = written.compareAndSet(false, true);
        var text  = first ? script.render(changeSets, Network.hostName()) : script.renderAddition(changeSets);
        write(text, first);
        log.info("{} the schema of {} change set(s), {} change(s), to '{}' - validating the database against it next",
                 first ? "Wrote" : "Appended",
                 changeSets.size(),
                 changeSets.stream().mapToInt(changeSet -> changeSet.changes().size()).sum(),
                 scriptFile.toAbsolutePath());
        validator.apply(changeSets);
    }

    private synchronized void write(String text, boolean replace) {
        try {
            var parent = scriptFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (replace) {
                Files.writeString(scriptFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                Files.writeString(scriptFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the schema script to '" + scriptFile.toAbsolutePath() + "'", e);
        }
    }
}
