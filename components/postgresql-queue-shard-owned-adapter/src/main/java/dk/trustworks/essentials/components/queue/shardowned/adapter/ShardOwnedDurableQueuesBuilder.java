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
 * an {@code int} that only mean anything together: auto-registration writes through the one using the
 * other. A positional constructor would also make the shard count look like a number to pass rather
 * than a decision with a default, which it is.
 */
public class ShardOwnedDurableQueuesBuilder {

    private MessageQueues                           queues;
    private JSONSerializer                          jsonSerializer;
    private UnitOfWorkFactory<? extends UnitOfWork> unitOfWorkFactory;
    private DataSource                              dataSource;
    /**
     * Defaults to {@link ShardOwnedDurableQueues#DEFAULT_AUTO_REGISTER_SHARD_COUNT}. Zero means
     * refuse, which has to be asked for now — see {@link #setAutoRegisterShardCount(int)}.
     */
    private int                                     autoRegisterShardCount = ShardOwnedDurableQueues.DEFAULT_AUTO_REGISTER_SHARD_COUNT;

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

    /**
     * Serializes message payloads and metadata into the stored envelope.
     */
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

    /**
     * Required unless {@link #setAutoRegisterShardCount(int)} is set to zero — registration writes through it.
     */
    public ShardOwnedDurableQueuesBuilder setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /**
     * Unordered shard count for a queue this adapter is asked for but nobody registered. Defaults to
     * {@link ShardOwnedDurableQueues#DEFAULT_AUTO_REGISTER_SHARD_COUNT}; <b>zero refuses</b>.
     *
     * <h2>Why the default registers rather than refuses</h2>
     * {@code DurableQueues} invents a queue on first use, and under this adapter almost every queue
     * an application has is invented: the demo's six were five framework-derived names and one of
     * its own. Nor are those names guessable ahead of time — an {@code EventProcessor} inbox is
     * {@code Inbox:<processorName>}, a {@code ViewEventProcessor} uses {@code <processorName>:queue},
     * an {@code Outbox} is {@code Outbox:<name>}, the command bus is {@code DefaultCommandQueue}.
     * Requiring them to be declared up front means hard-coding three framework conventions, so
     * refusing by default made this adapter a non-starter rather than a safe default.
     * <p>
     * What refusing was protecting against has also weakened. The count caps how many instances can
     * consume a queue's <em>unordered</em> lane, and it used to be effectively permanent. It is not:
     * {@code growShardCount} raises it in one call, running consumers pick it up on their next
     * heartbeat, and the ordered lane does not read it at all. Guessing low therefore costs an
     * instance ceiling until somebody raises it — online, no restart, no data loss. That is a smaller
     * risk than an inbox that silently never consumes.
     *
     * <h2>When to set it explicitly</h2>
     * Pass a larger number if one queue will be consumed by more instances than the default allows;
     * the measured knee is 4 and 8 buys 95% of what 16 does. Pass <b>0</b> to restore the refusal,
     * which is the right choice for an application that names all its own queues and would rather a
     * typo be an error than a registered queue nobody meant to create.
     * <p>
     * Applies to the unordered lane only. The ordered lane routes over the fixed space recorded on
     * the queue's registry row, so an auto-registered queue's ordered lane is already correct.
     */
    public ShardOwnedDurableQueuesBuilder setAutoRegisterShardCount(int autoRegisterShardCount) {
        this.autoRegisterShardCount = autoRegisterShardCount;
        return this;
    }

    public ShardOwnedDurableQueues build() {
        return new ShardOwnedDurableQueues(queues, jsonSerializer, unitOfWorkFactory, dataSource, autoRegisterShardCount);
    }
}
