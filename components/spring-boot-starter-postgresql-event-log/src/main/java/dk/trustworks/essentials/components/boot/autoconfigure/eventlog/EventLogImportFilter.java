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

import org.springframework.boot.autoconfigure.*;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Drops the old event-store starter's {@code EventStoreConfiguration} from the auto-configuration candidates while
 * {@code essentials.event-log.enabled=true}, and nothing else.
 * <p>
 * Its event store bean would be displaced by the adapter, but its subscription manager, durable subscription repository,
 * gap handler and CDC beans would not, and would be built on an adapter whose polling methods throw. The same starter's
 * {@code SnapshotConfiguration}, {@code ClosingBooksConfiguration} and aggregate admin configurations are kept: they need
 * only what {@link EventLogAutoConfiguration} declares. Old processors ({@code EventProcessor}, {@code ViewEventProcessor},
 * {@code InTransactionEventProcessor}) cannot run, which is intended: {@link UnportedEventProcessorGuard} fails start-up
 * naming each one before its missing {@code *Dependencies} bean is reached.
 * <p>
 * Registered in {@code META-INF/spring.factories}, Spring Boot's own mechanism for this.
 */
public final class EventLogImportFilter implements AutoConfigurationImportFilter, EnvironmentAware {
    /**
     * The one auto-configuration this filter drops
     */
    public static final String OLD_EVENT_STORE_CONFIGURATION = "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore.EventStoreConfiguration";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        var enabled = environment != null && environment.getProperty("essentials.event-log.enabled", Boolean.class, false);
        var matches = new boolean[autoConfigurationClasses.length];
        for (int i = 0; i < autoConfigurationClasses.length; i++) {
            matches[i] = !(enabled && OLD_EVENT_STORE_CONFIGURATION.equals(autoConfigurationClasses[i]));
        }
        return matches;
    }
}
