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
package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.architecture;

import dk.trustworks.essentials.components.foundation.test.architecture.AbstractEssentialsSchemaRulesTest;

/**
 * This starter's classpath reaches foundation, the fenced lock, the durable queues, the event store and the
 * aggregates - every PostgreSQL module the schema harness covers except the shard-owned engine, which its own
 * starter guards.
 */
class EssentialsSchemaRulesTest extends AbstractEssentialsSchemaRulesTest {
}
