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

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * What one {@link EssentialsSchemaContributor} contributed, as the {@link EssentialsSchemaHarness} hands it to the
 * {@link SchemaApplier}. The applier runs the changes of one set together, in one transaction.
 *
 * @param moduleId the contributing module - see {@link EssentialsSchemaContributor#moduleId()}
 * @param order    the contributor's position - see {@link SchemaOrder}
 * @param changes  the changes, in the order the contributor gave them
 */
public record SchemaChangeSet(String moduleId, int order, List<SchemaChange> changes) {

    public SchemaChangeSet {
        requireTrue(moduleId != null && !moduleId.isBlank(), "No moduleId provided");
        changes = List.copyOf(requireNonNull(changes, "No changes provided"));
    }
}
