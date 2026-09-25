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
import org.slf4j.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@code emit} mode: a pre-step that writes the complete schema as one script (see {@link PostgresqlSchemaScript})
 * and executes nothing. It needs no database connection - the script is rendered from what the contributors describe
 * - so it can run in a build or deployment pipeline. Hand the script to whoever holds DDL rights, then run the
 * application with {@link PostgresqlValidateSchemaApplier}, which refuses to start until the script has been run.
 * <p>
 * Objects a {@link DynamicSchemaContributor} registers later are appended to the same file as blocks of their own.
 */
public final class PostgresqlEmitSchemaApplier implements SchemaApplier {
    private static final Logger log = LoggerFactory.getLogger(PostgresqlEmitSchemaApplier.class);

    private final Path                   scriptFile;
    private final PostgresqlSchemaScript script;
    private final AtomicBoolean          written = new AtomicBoolean();

    /**
     * Records into {@link PostgresqlCreateSchemaApplier#DEFAULT_SCHEMA_HISTORY_TABLE_NAME}.
     *
     * @param scriptFile where the script is written - replaced on the first {@link #apply}
     */
    public PostgresqlEmitSchemaApplier(Path scriptFile) {
        this(scriptFile, PostgresqlCreateSchemaApplier.DEFAULT_SCHEMA_HISTORY_TABLE_NAME);
    }

    /**
     * @param scriptFile             where the script is written - replaced on the first {@link #apply}
     * @param schemaHistoryTableName the ledger table the script records into - the one the validate mode reads
     */
    public PostgresqlEmitSchemaApplier(Path scriptFile, String schemaHistoryTableName) {
        this.scriptFile = requireNonNull(scriptFile, "No scriptFile provided");
        this.script = new PostgresqlSchemaScript(schemaHistoryTableName);
    }

    public Path getScriptFile() {
        return scriptFile;
    }

    @Override
    public void apply(List<SchemaChangeSet> changeSets) {
        requireNonNull(changeSets, "No changeSets provided");
        var first = written.compareAndSet(false, true);
        var text  = first ? script.render(changeSets, Network.hostName()) : script.renderAddition(changeSets);
        write(text, first);
        log.info("{} the schema of {} change set(s), {} change(s), to '{}'",
                 first ? "Wrote" : "Appended",
                 changeSets.size(),
                 changeSets.stream().mapToInt(changeSet -> changeSet.changes().size()).sum(),
                 scriptFile.toAbsolutePath());
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
