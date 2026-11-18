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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class EventLogImportFilterTest {
    private static final String   PACKAGE    = "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.";
    private static final String[] CANDIDATES = {PACKAGE + "EventStoreConfiguration",
                                                PACKAGE + "SnapshotConfiguration",
                                                PACKAGE + "ClosingBooksConfiguration",
                                                PACKAGE + "AggregateLifecycleApiConfiguration",
                                                PACKAGE + "AggregateArchiveApiConfiguration",
                                                "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration"};

    @Test
    void drops_only_the_old_event_store_configuration_when_enabled() {
        assertThat(filter("true").match(CANDIDATES, null)).containsExactly(false, true, true, true, true, true);
    }

    @Test
    void keeps_everything_when_disabled_or_unset() {
        assertThat(filter("false").match(CANDIDATES, null)).containsOnly(true);
        assertThat(filter(null).match(CANDIDATES, null)).containsOnly(true);
    }

    @Test
    void the_constant_names_the_old_configuration() {
        assertThat(EventLogImportFilter.OLD_EVENT_STORE_CONFIGURATION)
                .isEqualTo(dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EventStoreConfiguration.class.getName());
    }

    @Test
    void the_starter_registers_the_filter() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                                        .load(AutoConfigurationImportFilter.class))
                .anyMatch(EventLogImportFilter.class::isInstance);
    }

    private static EventLogImportFilter filter(String enabled) {
        var environment = new MockEnvironment();
        if (enabled != null) {
            environment.setProperty("essentials.event-log.enabled", enabled);
        }
        var filter = new EventLogImportFilter();
        filter.setEnvironment(environment);
        return filter;
    }
}
