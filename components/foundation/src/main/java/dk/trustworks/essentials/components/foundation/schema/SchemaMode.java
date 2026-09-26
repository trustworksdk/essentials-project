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

/**
 * What happens to the schema the {@link EssentialsSchemaContributor}s describe - selected in Spring with
 * {@code essentials.schema.mode}.
 */
public enum SchemaMode {
    /**
     * Execute the statements and record them in the ledger. The default, and what every earlier release did: each
     * component still creates its own schema as it is constructed ({@link SchemaOwnership#COMPONENT})
     */
    CREATE,
    /**
     * Execute nothing; refuse to start unless every change is recorded in the ledger - for a database user without
     * DDL rights
     */
    VALIDATE,
    /**
     * A pre-step: write the complete schema as one script, execute nothing, and stop - to hand the script to whoever
     * holds DDL rights
     */
    EMIT,
    /**
     * Execute nothing and verify nothing: the schema is managed elsewhere, e.g. by Flyway or Liquibase
     */
    EXTERNAL;

    /**
     * @return who creates a component's schema in this mode: the component itself in {@link #CREATE}, the harness
     * otherwise
     */
    public SchemaOwnership schemaOwnership() {
        return this == CREATE ? SchemaOwnership.COMPONENT : SchemaOwnership.HARNESS;
    }
}
