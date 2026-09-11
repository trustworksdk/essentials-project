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
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
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
 * <b>{@code DurableQueues} is opt-in, and off by default.</b> The engine's own contract is
 * {@code MessageQueue}; it is not an implementation of {@code DurableQueues}, because the design
 * rejects roughly two fifths of that interface as artefacts of a claim-based queue. An adapter over
 * the part Inbox, Outbox and {@code DurableLocalCommandBus} actually use now exists, and
 * {@code essentials.shard-owned-queue.durable-queues-enabled} selects it — which moves every
 * {@code EventProcessor}'s projections onto this engine. It stays off unless asked, because a
 * starter on the classpath must not silently relocate the delivery path of an application that
 * wanted only the {@code MessageQueue} contract.
 * <p>
 * <b>What it does do</b> is the part that is unambiguous: one {@link ShardRuntime} for the process,
 * schema initialisation that is safe to run on every boot, the queues named in configuration
 * registered once, and a {@link ShardOwnedQueueFactory} for building queues against them. Handlers
 * stay the application's business — an auto-configuration cannot know what a message means.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class,
                  // By NAME, not by class: this starter must not take a compile dependency on
                  // spring-boot-starter-postgresql just to order itself. That starter declares
                  // DurableQueues @ConditionalOnMissingBean, so the selector below only wins if
                  // this configuration is evaluated first.
                  beforeName = "dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsConfiguration")
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
                    // Absent means the engine's default routing space, which is the answer for
                    // almost every queue. Naming one here is for the queue that needs more than 64
                    // instances on its ordered lane.
                    var orderedUnits = properties.getOrderedUnits()
                                                 .getOrDefault(entry.getKey(), ShardOwnedSchema.ORDERED_UNITS);
                    var registered = ShardOwnedSchema.registerQueue(dataSource, name, entry.getValue(), orderedUnits);
                    log.info("Registered queue '{}' as id {} with {} unordered shards and {} ordered units",
                             name, registered.queueId(), registered.shardCount(), orderedUnits);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to initialise the shard-owned queue schema", e);
            }
        }
    }

    /**
     * Runs the application's {@link DurableQueues} on this engine — {@code Inbox}, {@code Outbox},
     * {@code DurableLocalCommandBus}, and therefore every {@code EventProcessor}'s projections.
     * <p>
     * <b>Off unless asked.</b> Selected by
     * {@code essentials.shard-owned-queue.durable-queues-enabled}, and the default is {@code false}:
     * merely having this starter on the classpath must not relocate an application's delivery path.
     * <p>
     * <b>How it displaces the default.</b> {@code spring-boot-starter-postgresql} declares its
     * {@code PostgresqlDurableQueues} {@code @ConditionalOnMissingBean}, so defining this bean first
     * is the whole mechanism — hence the {@code beforeName} on the class. Turning the property off
     * restores the default with no other change, which is what makes the two engines an A/B.
     * <p>
     * <b>Why auto-registration is not optional here.</b> {@code DurableQueues} invents a queue on
     * first use and an {@code Inbox} is named by the processor that owns it, so those names cannot be
     * pre-declared in {@code essentials.shard-owned-queue.queues}. The context fails at start-up
     * rather than at the first unregistered inbox, because the alternative is an inbox that silently
     * never consumes.
     */
    @Bean
    @ConditionalOnMissingBean(DurableQueues.class)
    @ConditionalOnProperty(prefix = "essentials.shard-owned-queue", name = "durable-queues-enabled",
                           havingValue = "true")
    public DurableQueues shardOwnedDurableQueues(MessageQueues queues,
                                                 JSONSerializer jsonSerializer,
                                                 UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                                                 DataSource dataSource,
                                                 ShardOwnedQueueProperties properties) {
        log.info("Durable queues are running on the SHARD-OWNED engine — Inbox, Outbox and every "
                 + "EventProcessor's projections are delivered by it. Queues invented at runtime get "
                 + "{} unordered shards.", properties.getAutoRegisterShardCount());
        return ShardOwnedDurableQueues.builder()
                                      .setQueues(queues)
                                      .setJsonSerializer(jsonSerializer)
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setDataSource(dataSource)
                                      .setAutoRegisterShardCount(properties.getAutoRegisterShardCount())
                                      .build();
    }
}
