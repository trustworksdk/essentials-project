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

import dk.trustworks.essentials.components.adminapi.rest.AdminApiPrincipalResolver;
import dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.rest.*;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.api.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.SQLException;
import dk.trustworks.essentials.shared.network.Network;

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
     * The administrative surface, wired only when the admin API starter is on the classpath.
     *
     * <h2>Why this is conditional rather than always on</h2>
     * {@code spring-boot-starter-admin-api} brings the event-store starter with it. An application
     * that wants a queue and nothing else must not acquire an event store by depending on this
     * starter, so the dependency is {@code provided} and everything here is guarded by the presence
     * of the classes it needs. An application that already serves the admin API gets these endpoints
     * by adding no configuration at all.
     *
     * <h2>Why the controller is not in the admin API starter</h2>
     * The engine is unpublished. A controller there would give a published artifact a dependency on
     * an artifact in no repository, and would put a moving surface inside
     * {@code EssentialsAdminApiSpec}, whose contract is compatibility-checked. The endpoints
     * therefore serve under the admin API's base path and use its principal resolution and error
     * handling, but are not part of its declared contract — they will not appear in the generated
     * OpenAPI document, nor in the start-up summary of served contract areas. Adding the
     * {@code EssentialsAdminApiSpec} entries and moving the controller across is one step, and it
     * belongs with publishing the engine.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({RestController.class, AdminApiPrincipalResolver.class})
    public static class ShardOwnedQueuesAdminApiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(EssentialsSecurityProvider.class)
        public ShardOwnedQueuesApi shardOwnedQueuesApi(EssentialsSecurityProvider securityProvider,
                                                       ShardOwnedQueueFactory factory) {
            return new DefaultShardOwnedQueuesApi(securityProvider, factory);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean({ShardOwnedQueuesApi.class, AdminApiPrincipalResolver.class})
        public ShardOwnedQueuesController shardOwnedQueuesController(ShardOwnedQueuesApi shardOwnedQueuesApi,
                                                                     AdminApiPrincipalResolver principalResolver) {
            log.info("Shard-owned queue admin endpoints are served under the Essentials admin API base path");
            return new ShardOwnedQueuesController(shardOwnedQueuesApi, principalResolver);
        }

        /**
         * Renders this engine's {@code QueueName} as a JSON string.
         * <p>
         * Registered here rather than assumed, because the admin API's own Jackson module only covers
         * {@code CharSequenceType}, and this engine's {@code QueueName} deliberately is not one.
         * Without it the same concept renders as an object on these endpoints and as a string on the
         * durable-queues ones.
         */
        @Bean
        @ConditionalOnMissingBean
        public ShardOwnedQueueJacksonModule shardOwnedQueueJacksonModule() {
            return new ShardOwnedQueueJacksonModule();
        }
    }

    /**
     * The hostname, which is what the rest of Essentials uses to identify an instance — the fenced
     * lock manager and {@code DefaultEssentialsScheduler} both take {@code Network.hostName()} bare.
     * <p>
     * This was a random UUID per boot, on the reasoning that two instances sharing a host would
     * collide. They would, and a collision halves the fair share because two processes look like one
     * — but a UUID buys that at the cost of an id that means nothing in a log line, nothing in the
     * membership table, and changes on every restart. In the deployment that matters the hostname is
     * the pod name and is already unique, and where it is not, the fix is to set
     * {@code essentials.shard-owned-queue.instance-id} rather than to make every id unreadable.
     * <p>
     * Consistency with the rest of the framework also matters here: an operator correlating a shard
     * hand-over with a lock hand-over should not have to translate between two naming schemes.
     */
    private static String defaultInstanceId() {
        return Network.hostName();
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
