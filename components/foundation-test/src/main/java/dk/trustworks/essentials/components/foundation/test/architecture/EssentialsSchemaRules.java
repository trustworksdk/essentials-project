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
package dk.trustworks.essentials.components.foundation.test.architecture;

import com.tngtech.archunit.core.domain.*;
import com.tngtech.archunit.lang.*;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * The schema harness rule: DDL lives in schema contributors. A component that runs {@code CREATE}, {@code ALTER},
 * {@code DROP} or {@code TRUNCATE} itself is invisible to the harness - its objects are never validated, never part of
 * the emitted script, and run DDL the database user may not be allowed to run.
 * <p>
 * ArchUnit sees no string literals, so the condition reads each class file's constant pool. That is where the
 * compiler puts every literal, every text block, and every string-concatenation recipe, so
 * {@code "CREATE TABLE IF NOT EXISTS " + tableName + " (..."} is caught as a recipe string that starts with the
 * statement and holds a placeholder character where the table name goes.
 * What it cannot see is DDL that does not start a string constant: a statement assembled from fragments that do not
 * begin with the keyword, or one folded by the compiler into a larger concatenation behind other text. The rule is a
 * guard, not a proof.
 */
public final class EssentialsSchemaRules {
    private static final String CONTRIBUTOR = "dk.trustworks.essentials.components.foundation.schema.EssentialsSchemaContributor";

    /**
     * A statement - not a log line such as "Creating table": the keyword must be followed by what it creates.
     */
    static final Pattern DDL = Pattern.compile("^\\s*(CREATE|ALTER|DROP|TRUNCATE)\\s+(OR\\s+REPLACE\\s+)?(UNIQUE\\s+)?(TEMP(ORARY)?\\s+)?" +
                                               "(TABLE|INDEX|SEQUENCE|VIEW|MATERIALIZED\\s+VIEW|FUNCTION|PROCEDURE|TRIGGER|SCHEMA|EXTENSION|TYPE)\\b",
                                               Pattern.CASE_INSENSITIVE);

    /**
     * The classes that legitimately hold DDL without being a schema contributor, each with the reason. Adding one is a
     * decision to review, not a way past a red build: the default answer is to make the class a contributor.
     */
    public static final Map<String, String> ALLOWED_DDL_HOLDERS = Map.of(
            "dk.trustworks.essentials.components.queue.postgresql.DurableQueuesSql",
            "the statements of PostgresqlDurableQueues, which contributes them",
            "dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcSql",
            "the statements of CdcInboxRepository, which contributes them",
            "dk.trustworks.essentials.components.foundation.postgresql.PostgresqlSchemaHistory",
            "the schema history ledger itself, created by the appliers before anything they apply",
            "dk.trustworks.essentials.components.foundation.postgresql.ListenNotify",
            "public helper for an application's own tables; framework components use changeNotificationTriggerStatements in their contributions",
            "dk.trustworks.essentials.components.foundation.postgresql.api.DefaultPostgresqlQueryStatisticsApi",
            "best-effort CREATE EXTENSION pg_stat_statements, only when available and tolerated when refused - an operator's choice, not application schema",
            "dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema",
            "the shard-owned engine's own schema - the engine depends only on shared; ShardOwnedSchemaContributor carries it into the harness");

    private EssentialsSchemaRules() {
    }

    /**
     * @param allowed classes allowed to hold DDL although they are no {@code EssentialsSchemaContributor}, by fully
     *                qualified name - see {@link #ALLOWED_DDL_HOLDERS}
     * @return the rule: a class holding a DDL statement is a schema contributor, is nested in one, or is allowed
     */
    public static ArchRule ddlLivesInSchemaContributors(Set<String> allowed) {
        return classes().should(new ArchCondition<>("hold DDL statements only if they are a schema contributor") {
                            @Override
                            public void check(JavaClass javaClass, ConditionEvents events) {
                                if (isContributorOrNestedInOne(javaClass) || isOrIsNestedIn(javaClass, allowed)) {
                                    return;
                                }
                                for (var constant : stringConstants(javaClass)) {
                                    if (DDL.matcher(constant).find()) {
                                        events.add(SimpleConditionEvent.violated(javaClass,
                                                                                 javaClass.getName() + " holds DDL but is no schema contributor: " + firstLine(constant)));
                                    }
                                }
                            }
                        })
                        .because("the schema harness only validates, emits or creates what a contributor describes");
    }

    private static boolean isContributorOrNestedInOne(JavaClass javaClass) {
        for (Optional<JavaClass> current = Optional.of(javaClass); current.isPresent(); current = current.get().getEnclosingClass()) {
            if (current.get().isAssignableTo(CONTRIBUTOR)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOrIsNestedIn(JavaClass javaClass, Set<String> names) {
        for (Optional<JavaClass> current = Optional.of(javaClass); current.isPresent(); current = current.get().getEnclosingClass()) {
            if (names.contains(current.get().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String firstLine(String constant) {
        var line = constant.strip().lines().findFirst().orElse("").replace('\u0001', '?');
        return line.length() > 100 ? line.substring(0, 100) + "..." : line;
    }

    /**
     * The {@code CONSTANT_String} entries of the class file - see JVMS 4.4
     */
    static List<String> stringConstants(JavaClass javaClass) {
        var source = javaClass.getSource();
        if (source.isEmpty()) {
            return List.of();
        }
        try (var in = new DataInputStream(new BufferedInputStream(source.get().getUri().toURL().openStream()))) {
            in.readInt();                                  // magic
            in.readUnsignedShort();                        // minor
            in.readUnsignedShort();                        // major
            var count        = in.readUnsignedShort();
            var utf8         = new String[count];
            var stringRefs   = new ArrayList<Integer>();
            for (var index = 1; index < count; index++) {
                var tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[index] = in.readUTF();
                    case 8 -> stringRefs.add(in.readUnsignedShort());
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        index++;                           // eight-byte constants take two slots
                    }
                    case 7, 16, 19, 20 -> in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IllegalStateException("Unknown constant pool tag " + tag + " in " + javaClass.getName());
                }
            }
            var strings = new ArrayList<String>(stringRefs.size());
            for (var ref : stringRefs) {
                if (utf8[ref] != null) {
                    strings.add(utf8[ref]);
                }
            }
            return strings;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the class file of " + javaClass.getName(), e);
        }
    }
}
