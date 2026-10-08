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

package dk.trustworks.essentials.components.foundation.boot;

import org.slf4j.*;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.context.properties.source.*;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Logs an ERROR for every {@code essentials.*} property that a release removed but the application still sets.
 * <p>
 * Spring Boot binds configuration leniently: a key that no {@code @ConfigurationProperties} class declares any more is
 * ignored without a word. For most removed keys that only leaves dead configuration behind, but
 * {@code essentials.durable-queues.transactional-mode=fully-transactional} changes transaction semantics when it stops
 * applying, so an application that still sets it must hear about it. The starters' configuration metadata marks the
 * same keys deprecated at level {@code error} for the IDE; this is the runtime half.
 * <p>
 * Runs as a {@link BeanFactoryPostProcessor}, i.e. before any bean is created, so the ERROR is logged even when the
 * context later fails to start. Keys are matched the way Spring Boot binds them (relaxed: kebab-case, camelCase,
 * environment variables), and the message names where the value came from.
 */
public final class RemovedEssentialsPropertiesCheck implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final Logger log = LoggerFactory.getLogger(RemovedEssentialsPropertiesCheck.class);

    private static final String TRANSACTIONAL_MODE_GUIDANCE =
            "TransactionalMode was removed: every queue operation now runs in its own transaction, as SingleOperationTransaction did. "
                    + "If it was set to fully-transactional, delivery semantics changed: a UnitOfWork.markAsRollbackOnly() in a message handler no longer rolls "
                    + "the message handling back, and an enqueue no longer requires or joins an enclosing UnitOfWork for its write - call queueMessage inside "
                    + "your own UnitOfWork to keep the entity change and the enqueue atomic. Remove the property. "
                    + "See docs/MIGRATION-0.60.md, 'TransactionalMode is retired'.";

    private static final String QUEUE_STATISTICS_GUIDANCE =
            "The trigger-based durable-queue statistics were removed, with their table, trigger and TTL job. Per-instance statistics "
                    + "(QueueStatisticsRegistry, GET /durable-queues/queues/{queueName}/statistics) replace them and need no property. "
                    + "Remove the property. See docs/MIGRATION-0.60.md, 'The queue statistics feature is removed'.";

    /**
     * Every removed {@code essentials.*} key, in canonical (kebab-case) form, with what the application should do.
     * Keep in step with the error-level entries in the starters' {@code additional-spring-configuration-metadata.json}.
     */
    static final Map<String, String> REMOVED_PROPERTIES = Map.of(
            "essentials.durable-queues.transactional-mode", "Removed in 0.60.0. " + TRANSACTIONAL_MODE_GUIDANCE,
            "essentials.durable-queues.use-ordered-unordered-query",
            "Removed in 0.60.0. The unified claim query it selected is gone, and the ordered/unordered pair is the only claim path - "
                    + "the behaviour of the former default (true). Remove the property. See docs/MIGRATION-0.60.md, 'useOrderedUnorderedQuery is removed'.",
            "essentials.durable-queues.enable-queue-statistics", "Removed in 0.60.0. " + QUEUE_STATISTICS_GUIDANCE,
            "essentials.durable-queues.shared-queue-statistics-table-name", "Removed in 0.60.0. " + QUEUE_STATISTICS_GUIDANCE,
            "essentials.durable-queues.enable-queue-statistics-ttl", "Removed in 0.60.0. " + QUEUE_STATISTICS_GUIDANCE,
            "essentials.durable-queues.queue-statistics-ttl-duration", "Removed in 0.60.0. " + QUEUE_STATISTICS_GUIDANCE);

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = requireNonNull(environment, "No environment provided");
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (environment != null) {
            findRemovedPropertiesStillSet(environment).forEach(log::error);
        }
    }

    /**
     * @param environment the environment to inspect
     * @return one message per removed {@code essentials.*} property that is still set, sorted by property name
     */
    static List<String> findRemovedPropertiesStillSet(Environment environment) {
        requireNonNull(environment, "No environment provided");
        var messages = new ArrayList<String>();
        new TreeMap<>(REMOVED_PROPERTIES).forEach((property, guidance) -> {
            var name = ConfigurationPropertyName.of(property);
            for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                var configured = source.getConfigurationProperty(name);
                if (configured != null) {
                    messages.add("Essentials: the property '" + property + "' is set (" + describeOrigin(configured) + ") but is no longer supported and is ignored. " + guidance);
                    return;
                }
            }
        });
        return messages;
    }

    private static String describeOrigin(ConfigurationProperty configured) {
        var origin = configured.getOrigin();
        return "value '" + configured.getValue() + "'" + (origin != null ? ", from " + origin : "");
    }
}
