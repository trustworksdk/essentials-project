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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.architecture;

import dk.trustworks.essentials.components.foundation.test.architecture.AbstractEssentialsConstructionErgonomicsTest;

/**
 * The construction-ergonomics guard over the PostgreSQL implementation stack. Everything it does is inherited from
 * {@link AbstractEssentialsConstructionErgonomicsTest}; this class only decides <em>which classes</em> the rules run
 * against, by existing in this module.
 * <p>
 * It lives here because this starter's classpath is the only one in the reactor that carries {@code postgresql-queue}
 * and {@code postgresql-distributed-fenced-lock} — {@code eventsourced-aggregates}, which hosts the same guard over
 * the event-sourcing stack, reaches neither. The MongoDB counterparts are covered by the identical test in
 * {@code spring-boot-starter-mongodb}.
 *
 * @see AbstractEssentialsConstructionErgonomicsTest
 */
class EssentialsConstructionErgonomicsTest extends AbstractEssentialsConstructionErgonomicsTest {
}
