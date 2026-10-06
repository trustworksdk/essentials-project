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

/**
 * The describe-then-apply seam for the database objects Essentials needs: components declare their schema as
 * {@link dk.trustworks.essentials.components.foundation.schema.SchemaChange}s through an
 * {@link dk.trustworks.essentials.components.foundation.schema.EssentialsSchemaContributor}, and a
 * {@link dk.trustworks.essentials.components.foundation.schema.SchemaApplier} decides what happens to them.
 * {@link dk.trustworks.essentials.components.foundation.schema.EssentialsSchemaHarness} collects and orders the
 * contributions and hands them over.
 * <p>
 * Design and sequencing: {@code docs/database-schema-harness.md}.
 */
package dk.trustworks.essentials.components.foundation.schema;
