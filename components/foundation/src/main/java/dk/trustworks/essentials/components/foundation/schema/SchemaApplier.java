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

import dk.trustworks.essentials.shared.FailFast;

import java.util.List;

/**
 * Decides what happens to the schema the {@link EssentialsSchemaContributor}s described: execute it, verify it,
 * write it out, or leave it to somebody else.
 */
public interface SchemaApplier {

    /**
     * @param changeSets one set per contributor, already ordered by {@link SchemaChangeSet#order()} and module id.
     *                   Every change's identity - module, change id, object name - is unique across the list
     * @throws RuntimeException if the schema cannot be brought into the described shape - the caller treats it as a
     *                          startup failure
     */
    void apply(List<SchemaChangeSet> changeSets);

    /**
     * @param contributor the contributor whose later registrations the sink receives
     * @return a sink that applies each batch of changes as one change set of {@code contributor}
     */
    default SchemaChangeSink sinkFor(EssentialsSchemaContributor contributor) {
        FailFast.requireNonNull(contributor, "No contributor provided");
        return changes -> apply(List.of(new SchemaChangeSet(contributor.moduleId(), contributor.order(), changes)));
    }
}
