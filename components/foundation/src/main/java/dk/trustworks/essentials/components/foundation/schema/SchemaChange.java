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
package dk.trustworks.essentials.components.foundation.schema;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * One unit of schema a contributor needs: the statements that bring a single database object into the shape the
 * contributor expects.
 * <p>
 * <b>Every statement must be safe to run against a database where the object already exists in that shape</b> -
 * {@code CREATE ... IF NOT EXISTS}, {@code DROP ... IF EXISTS}, {@code ADD COLUMN IF NOT EXISTS}. That is how a
 * database created by an Essentials release older than the schema harness is adopted: on first boot the statements
 * run, change nothing, and the ledger records them (see {@code docs/database-schema-harness.md} §7).
 *
 * @param changeId   stable identifier of the change within its module, e.g. {@code "queue-table"}. Never reuse one
 *                   for different statements: a one-shot change is recognised by it
 * @param objectName the resolved, validated name of the database object this change owns. Part of the ledger key,
 *                   because one change is routinely applied to several objects - one event-stream table per
 *                   aggregate type, or differently configured table names in one database
 * @param statements executed in order, in the same transaction as the rest of the contributor's changes
 * @param repeatable {@code true}: runs on every boot, which is how every Essentials DDL statement behaved before the
 *                   harness. {@code false}: runs once per {@code (module, changeId, objectName)} and is then skipped;
 *                   editing its statements afterwards fails startup
 */
public record SchemaChange(String changeId, String objectName, List<String> statements, boolean repeatable) {

    public SchemaChange {
        requireTrue(changeId != null && !changeId.isBlank(), "No changeId provided");
        requireTrue(objectName != null && !objectName.isBlank(), "No objectName provided for change '" + changeId + "'");
        requireNonEmpty(requireNonNull(statements, "No statements provided"), "No statements provided for change '" + changeId + "'");
        statements = List.copyOf(statements);
        statements.forEach(statement -> requireTrue(!statement.isBlank(), "Change '" + changeId + "' contains a blank statement"));
    }

    /**
     * @return a change that runs on every boot
     */
    public static SchemaChange repeatable(String changeId, String objectName, String... statements) {
        return new SchemaChange(changeId, objectName, List.of(requireNonNull(statements, "No statements provided")), true);
    }

    /**
     * @return a change that runs once per {@code (module, changeId, objectName)}
     */
    public static SchemaChange once(String changeId, String objectName, String... statements) {
        return new SchemaChange(changeId, objectName, List.of(requireNonNull(statements, "No statements provided")), false);
    }

    /**
     * @return SHA-256 over the statements, exactly as given. What the ledger compares to tell an unchanged change from
     * one edited after it shipped
     */
    public String checksum() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var statement : statements) {
                digest.update(statement.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
