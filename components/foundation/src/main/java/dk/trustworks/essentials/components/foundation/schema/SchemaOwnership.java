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
 * Who applies a component's schema.
 */
public enum SchemaOwnership {
    /**
     * The component applies its own schema, with the {@code create} applier, at the point it always did - on
     * construction or when its storage initialises. The default: an application that knows nothing about the schema
     * harness behaves exactly as it did before it existed.
     */
    COMPONENT,
    /**
     * An {@link EssentialsSchemaHarness} is responsible: the component only describes its schema, as an
     * {@link EssentialsSchemaContributor}, and never executes DDL itself. The Spring Boot starters select this once they
     * run the harness.
     */
    HARNESS
}
