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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.ShardOwnedQueueFactory;
import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.queue.shardowned.adapter.ShardOwnedDurableQueues;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;

/**
 * Runs the application's {@link DurableQueues} on the shard-owned engine instead of the default
 * {@code PostgresqlDurableQueues}.
 *
 * <h2>Why this is the showcase that matters</h2>
 * {@code QueueLoadGenerator} drives the engine's own SPI with handlers that sleep for a millisecond.
 * That measures the engine and nothing else, which is useful and also unlike any real application.
 * This bean puts the engine underneath work the demo was already doing: every
 * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor}
 * forwards the events it consumes through an {@code Inbox}, and an {@code Inbox} is a
 * {@code DurableQueues} queue. So with this in place the demo's projections — account statement,
 * trade valuation, settlement status — are all delivered by the shard-owned engine, and their
 * handlers do what handlers really do: open a unit of work and write SQL.
 *
 * <h2>Why it is behind a property</h2>
 * Both engines implement the same contract, so the interesting question is not "does it work" but
 * "what changes". Leaving the default in place unless asked makes the demo an A/B rather than a
 * one-way door: start it with {@code trading-demo.shard-owned-durable-queues=false} for the
 * behaviour to compare against.
 *
 * <h2>Auto-registration is required here, not optional</h2>
 * The shard-owned engine will not invent a queue, because a queue's shard count and ordered routing
 * space are properties of its stored data. {@code DurableQueues} callers do invent queues —
 * {@code Inbox:AccountStatementProjection} is created by the processor, not by configuration — so an
 * application on this adapter has to say what an invented queue should look like.
 */
@Configuration
@ConditionalOnProperty(prefix = "trading-demo", name = "shard-owned-durable-queues",
                       havingValue = "true", matchIfMissing = true)
public class ShardOwnedDurableQueuesConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDurableQueuesConfiguration.class);

    /**
     * Takes precedence over the starter's {@code PostgresqlDurableQueues}, which is declared
     * {@code @ConditionalOnMissingBean}.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public DurableQueues durableQueues(ShardOwnedQueueFactory queues,
                                       JSONSerializer jsonSerializer,
                                       UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory,
                                       DataSource dataSource) {
        log.info("Durable queues are running on the SHARD-OWNED engine — Inbox, Outbox and every "
                 + "EventProcessor's projections are delivered by it");
        return ShardOwnedDurableQueues.builder()
                                      .setQueues(queues)
                                      .setJsonSerializer(jsonSerializer)
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setDataSource(dataSource)
                                      // Inboxes are named by the processor that owns them, so they
                                      // cannot be pre-declared in configuration.
                                      .setAutoRegisterShardCount(4)
                                      .build();
    }
}
