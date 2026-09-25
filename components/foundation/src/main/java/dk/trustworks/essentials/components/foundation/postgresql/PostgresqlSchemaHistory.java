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
import org.jdbi.v3.core.Handle;

import java.util.*;

/**
 * The schema ledger's shape and how it is read - shared by the PostgreSQL appliers, so the create applier, the
 * validator and the emitted script all agree on one table.
 */
final class PostgresqlSchemaHistory {
    private PostgresqlSchemaHistory() {
    }

    /**
     * A ledger row's identity
     */
    record ChangeKey(String moduleId, String changeId, String objectName) {
        static ChangeKey of(SchemaChangeSet changeSet, SchemaChange change) {
            return new ChangeKey(changeSet.moduleId(), change.changeId(), change.objectName());
        }
    }

    /**
     * @param tableName an already validated table name
     */
    static String createTableStatement(String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n" +
                "    module_id   TEXT        NOT NULL,\n" +
                "    change_id   TEXT        NOT NULL,\n" +
                "    object_name TEXT        NOT NULL,\n" +
                "    checksum    TEXT        NOT NULL,\n" +
                "    applied_ts  TIMESTAMPTZ NOT NULL,\n" +
                "    applied_by  TEXT        NOT NULL,\n" +
                "    PRIMARY KEY (module_id, change_id, object_name)\n" +
                ")";
    }

    static boolean exists(Handle handle, String tableName) {
        return handle.createQuery("SELECT to_regclass(:tableName) IS NOT NULL")
                     .bind("tableName", tableName)
                     .mapTo(Boolean.class)
                     .one();
    }

    /**
     * @param onlyModuleId {@code null} for every module
     * @return recorded checksum per change
     */
    static Map<ChangeKey, String> read(Handle handle, String tableName, String onlyModuleId) {
        var query = handle.createQuery("SELECT module_id, change_id, object_name, checksum FROM " + tableName +
                                               (onlyModuleId != null ? " WHERE module_id = :moduleId" : ""));
        if (onlyModuleId != null) {
            query.bind("moduleId", onlyModuleId);
        }
        var result = new HashMap<ChangeKey, String>();
        query.map((rs, ctx) -> Map.entry(new ChangeKey(rs.getString("module_id"), rs.getString("change_id"), rs.getString("object_name")),
                                         rs.getString("checksum")))
             .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
