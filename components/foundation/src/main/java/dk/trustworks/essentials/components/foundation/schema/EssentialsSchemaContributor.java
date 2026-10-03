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

import java.util.List;

/**
 * A component that needs database objects, and describes them instead of creating them itself.
 * <p>
 * The {@link EssentialsSchemaHarness} asks every contributor for its changes, orders them by {@link #order()}, and
 * hands them to its {@link SchemaApplier}, which decides whether they are executed, verified, emitted as a script or
 * left to an external tool. A contributor never executes DDL.
 */
public interface EssentialsSchemaContributor {

    /**
     * @return a stable identifier of the contributing module, e.g. {@code "postgresql-queue"}. Part of the ledger key,
     * so it must not change between releases
     */
    String moduleId();

    /**
     * @return this contributor's position - one of the {@link SchemaOrder} constants
     */
    int order();

    /**
     * @param context what the harness resolved for this run
     * @return the changes this contributor needs, in the order they must be applied. May be empty
     */
    List<SchemaChange> contribute(SchemaContext context);
}
