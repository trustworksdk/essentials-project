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
 * Where a {@link DynamicSchemaContributor} hands the changes of an object registered after the
 * {@link EssentialsSchemaHarness} ran. Obtained from {@link SchemaApplier#sinkFor(EssentialsSchemaContributor)}.
 */
@FunctionalInterface
public interface SchemaChangeSink {

    /**
     * @param changes the changes of one newly registered object
     * @throws RuntimeException if the applier cannot bring the object into the described shape - the registration
     *                          fails
     */
    void apply(List<SchemaChange> changes);

    /**
     * @return whether {@link #apply} executes the changes - see {@link SchemaApplier#createsSchema()}
     */
    default boolean createsSchema() {
        return false;
    }
}
