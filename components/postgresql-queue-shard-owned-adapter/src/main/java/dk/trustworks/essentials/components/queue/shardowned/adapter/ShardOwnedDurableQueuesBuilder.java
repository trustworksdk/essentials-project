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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues;

import javax.sql.DataSource;

/**
 * Builds a {@link ShardOwnedDurableQueues}.
 * <p>
 * A builder rather than a constructor because two of the five arguments are a {@link DataSource} and
 * an {@code int} that only mean anything together, and the {@code int}'s default is the safe one — a
 * positional constructor makes "no auto-registration" look like an omission rather than a decision.
 */
public class ShardOwnedDurableQueuesBuilder {

    private MessageQueues                           queues;
    private JSONSerializer                          jsonSerializer;
    private UnitOfWorkFactory<? extends UnitOfWork>  unitOfWorkFactory;
    private DataSource                              dataSource;
    private int                                     autoRegisterShardCount;

    /**
     * Where queues are looked up by name.
     * <p>
     * Pass the <em>same</em> instance the rest of the application uses — in Spring, the
     * {@code ShardOwnedQueueFactory} bean. Two registries in one process would each build their own
     * {@code MessageQueue} for a name, and the two would register as two competing consumers and be
     * allowed half the shards each.
     */
    public ShardOwnedDurableQueuesBuilder setQueues(MessageQueues queues) {
        this.queues = queues;
        return this;
    }

    /** Serializes message payloads and metadata into the stored envelope. */
    public ShardOwnedDurableQueuesBuilder setJsonSerializer(JSONSerializer jsonSerializer) {
        this.jsonSerializer = jsonSerializer;
        return this;
    }

    /**
     * Optional. When present and a {@code HandleAwareUnitOfWork} is in progress, enqueued messages are
     * written on that unit of work's connection and commit with the caller's own work — which is what
     * makes an Outbox an Outbox. Without one, every enqueue commits on its own.
     */
    public ShardOwnedDurableQueuesBuilder setUnitOfWorkFactory(UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /** Required only when {@link #setAutoRegisterShardCount(int)} is used — registration writes to it. */
    public ShardOwnedDurableQueuesBuilder setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /**
     * Register an unknown queue name on first use, with this many <em>unordered</em> shards. Zero —
     * the default — fails instead.
     *
     * <h2>This number is the unordered lane's alone</h2>
     * The ordered lane does not take a shard count. It routes keys over a fixed space
     * ({@code ShardOwnedSchema.ORDERED_UNITS}) that is recorded on the queue's registry row when the
     * queue is registered and never changes afterwards, so an auto-registered queue's ordered lane is
     * already correct whatever is passed here.
     *
     * <h2>Read this before setting it</h2>
     * {@code DurableQueues} invents a queue on first use, so {@code getOrCreateInbox("x")} on a name
     * nobody configured is normal there. The shard-owned engine will not invent a shard count, because
     * the count caps how many instances can ever consume the queue's unordered lane and can be raised
     * but never lowered.
     * <p>
     * Correcting it later is cheap: {@code growShardCount} is a single call that running consumers
     * pick up on their next heartbeat, with no restart, no deploy and nothing to drain.
     * <p>
     * So this is a convenience worth having, not a trap: 8 is a reasonable value if you want it — the
     * measured knee is at 4, and 8 buys 95% of what 16 does. The default is still to leave it alone,
     * for one reason only: a queue that appears by accident (a typo in an inbox name) is then a
     * registered queue nobody meant to create, rather than an error.
     */
    public ShardOwnedDurableQueuesBuilder setAutoRegisterShardCount(int autoRegisterShardCount) {
        this.autoRegisterShardCount = autoRegisterShardCount;
        return this;
    }

    public ShardOwnedDurableQueues build() {
        return new ShardOwnedDurableQueues(queues, jsonSerializer, unitOfWorkFactory, dataSource, autoRegisterShardCount);
    }
}
