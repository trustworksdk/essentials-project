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

import dk.trustworks.essentials.components.foundation.test.architecture.AbstractEssentialsConstructionErgonomicsTest;

/**
 * The construction-ergonomics guard over the full Spring event-sourcing stack. Everything it does is inherited from
 * {@link AbstractEssentialsConstructionErgonomicsTest}; this class only decides <em>which classes</em> the rules run
 * against, by existing in this module.
 * <p>
 * This starter's classpath is a superset of both {@code eventsourced-aggregates}' and
 * {@code spring-boot-starter-postgresql}', so its store duplicates most of theirs — see the class javadoc on the base
 * for why overlapping stores are the accepted trade-off. What only this classpath reaches is
 * {@code spring-postgresql-event-store} and this module's own auto-configuration, which is what the test is here for.
 *
 * @see AbstractEssentialsConstructionErgonomicsTest
 */
class EssentialsConstructionErgonomicsTest extends AbstractEssentialsConstructionErgonomicsTest {
}
