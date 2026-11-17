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

package dk.trustworks.essentials.components.eventlog.migration.architecture;

import dk.trustworks.essentials.components.foundation.test.architecture.AbstractEssentialsSchemaRulesTest;

/**
 * The migration holds no DDL: the event log's tables come from the engine's contributor, and {@code setval} and
 * {@code LOCK TABLE} are not DDL (design phase 9 §3.6).
 */
class SchemaRulesTest extends AbstractEssentialsSchemaRulesTest {
}
