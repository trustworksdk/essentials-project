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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Auto-configuration for the shard-owned PostgreSQL queue engine.
 * <p>
 * <b>What this deliberately does not do.</b> It configures the engine's own {@code MessageQueue}
 * contract, not {@code DurableQueues}. The engine is not an implementation of that interface — the
 * design rejects roughly two fifths of it as artefacts of a claim-based queue — so nothing in the
 * surrounding Essentials machinery (Inboxes, Outboxes, EventProcessor, the admin API) is wired up
 * here. Whether an adapter should exist is an open decision, and a starter that quietly implied one
 * either way would be making it.
 * <p>
 * <b>What it does do</b> is the part that is unambiguous: one {@link ShardRuntime} for the process,
 * schema initialisation that is safe to run on every boot, the queues named in configuration
 * registered once, and a {@link ShardOwnedQueueFactory} for building queues against them. Handlers
 * stay the application's business — an auto-configuration cannot know what a message means.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(ShardOwnedQueue.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "essentials.shard-owned-queue", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ShardOwnedQueueProperties.class)
public class ShardOwnedQueueAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedQueueAutoConfiguration.class);

    /**
     * The engine's tuning, as one object, so a queue and the runtime cannot be configured from
     * different halves of the same properties.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardOwnerSettings shardOwnerSettings(ShardOwnedQueueProperties properties) {
        return properties.toSettings();
    }

    /**
     * <b>One runtime for the process</b>, which is the single most consequential line in this class.
     * The pumps, the LISTEN connection, the heartbeat and the handler executor are all process-wide;
     * a runtime per queue costs {@code pumpThreads + 1} connections each, and a hundred queues once
     * exhausted a 500-connection pool. Spring manages its lifecycle, so it stops with the context.
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public ShardRuntime shardRuntime(DataSource dataSource, ShardOwnerSettings settings) {
        return new ShardRuntime(dataSource, settings);
    }

    /**
     * Creates the schema if it is absent and registers the configured queues, before anything that
     * might use them.
     * <p>
     * Separated from the factory bean so that a deployment whose schema is owned by a migration tool
     * can turn initialisation off and still get everything else.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardOwnedQueueInitializer shardOwnedQueueInitializer(DataSource dataSource,
                                                                ShardOwnedQueueProperties properties) {
        return new ShardOwnedQueueInitializer(dataSource, properties);
    }

    /**
     * Builds queues by name against the shared runtime.
     * <p>
     * A factory rather than a bean per queue: the queues are configured as data, and a name declared
     * in {@code application.yml} cannot become a bean name without inventing a mapping that would
     * then be part of the contract.
     */
    @Bean
    @ConditionalOnMissingBean
    public ShardOwnedQueueFactory shardOwnedQueueFactory(DataSource dataSource,
                                                         ShardRuntime runtime,
                                                         ShardOwnerSettings settings,
                                                         ShardOwnedQueueProperties properties,
                                                         ShardOwnedQueueInitializer initializer,
                                                         ObjectProvider<MessageQueueInterceptor> interceptors,
                                                         ObjectProvider<QueueObserver> observers) {
        var instanceId = properties.getInstanceId() != null && !properties.getInstanceId().isBlank()
                         ? properties.getInstanceId()
                         : defaultInstanceId();
        log.info("Shard-owned queue engine: instanceId '{}', {} pump thread(s), queues {}",
                 instanceId, settings.pumpThreads(), properties.getQueues().keySet());
        // ObjectProvider rather than List injection, so an application with none of either does not
        // need to declare an empty bean to satisfy the constructor.
        return new ShardOwnedQueueFactory(dataSource, runtime, settings, instanceId, initializer,
                                          interceptors.orderedStream().toList(),
                                          observers.orderedStream().toList());
    }

    /**
     * A random id per boot. Deliberately not the hostname: two instances on one host would collide,
     * and a colliding instance id makes two processes look like one to the fair-share rebalance.
     * Set {@code essentials.shard-owned-queue.instance-id} where the platform offers something both
     * stable and unique.
     */
    private static String defaultInstanceId() {
        return "shard-owned-" + UUID.randomUUID();
    }

    /**
     * Runs the engine's schema and queue registration at start-up.
     * <p>
     * A bean rather than a call inside the factory, so that its failure fails the context. Silently
     * carrying on with a half-created schema would turn a configuration error into a delivery bug
     * discovered much later.
     */
    public static class ShardOwnedQueueInitializer {
        private final DataSource                dataSource;
        private final ShardOwnedQueueProperties properties;

        public ShardOwnedQueueInitializer(DataSource dataSource, ShardOwnedQueueProperties properties) {
            this.dataSource = dataSource;
            this.properties = properties;
            initialize();
        }

        private void initialize() {
            try {
                if (properties.isInitializeSchema()) {
                    // Non-destructive, idempotent, and serialised across instances by the framework's
                    // bootstrap advisory lock — PostgreSQL's IF NOT EXISTS is not atomic against
                    // concurrent sessions, and every instance runs this at the same moment.
                    ShardOwnedSchema.initialize(dataSource);
                }
                for (var entry : properties.getQueues().entrySet()) {
                    var name = QueueName.of(entry.getKey());
                    var registered = ShardOwnedSchema.registerQueue(dataSource, name, entry.getValue());
                    log.info("Registered queue '{}' as id {} with {} shards",
                             name, registered.queueId(), registered.shardCount());
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialise the shard-owned queue schema", e);
            }
        }
    }
}
