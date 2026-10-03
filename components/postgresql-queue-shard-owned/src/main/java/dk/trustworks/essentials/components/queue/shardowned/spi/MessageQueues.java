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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import java.sql.SQLException;
import java.util.*;

/**
 * The queues a process can reach, by name.
 * <p>
 * {@link MessageQueue} is deliberately bound to one queue — its {@code queue_id} is resolved once and
 * then used as a bind parameter on every statement, which is what lets a single {@code ShardRuntime}
 * serve hundreds of queues from five connections. That leaves nothing to ask "which queues exist?",
 * and an administrative caller needs exactly that before it can ask anything else.
 * <p>
 * This is the smallest interface that closes the gap. It is not a factory: implementations decide
 * whether a returned queue is created on demand or handed out from a map, and nothing here starts a
 * consumer. A caller that only wants to inspect a queue must not, by inspecting it, cause the process
 * to start competing for its shards.
 *
 * @see dk.trustworks.essentials.components.queue.shardowned.api.ShardOwnedQueuesApi
 */
public interface MessageQueues {

    /**
     * Every queue name in the registry, whether or not this process consumes from it.
     * <p>
     * Read from {@code shard_queue_registry} rather than from what this process happens to have
     * built, because an administrator asking what exists is not asking what this pod is doing.
     */
    List<QueueName> queueNames() throws SQLException;

    /**
     * The queue registered under {@code queueName}, or empty if the name is not in the registry.
     * <p>
     * Empty rather than a thrown exception: an unknown name arriving from outside the process is an
     * ordinary "not found", not a programming error.
     */
    Optional<MessageQueue> findQueue(QueueName queueName) throws SQLException;
}
