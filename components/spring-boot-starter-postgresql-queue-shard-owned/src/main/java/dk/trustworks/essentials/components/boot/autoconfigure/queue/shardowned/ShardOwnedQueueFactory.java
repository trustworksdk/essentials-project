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

import dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned.ShardOwnedQueueAutoConfiguration.ShardOwnedQueueInitializer;
import dk.trustworks.essentials.components.queue.shardowned.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Hands out {@link MessageQueue}s by name, all sharing this process's one {@link ShardRuntime}.
 * <p>
 * Queues are cached per name, and that is a correctness property rather than an optimisation: two
 * {@code MessageQueue} instances for one name in one process would register as two competing
 * consumers, halve each other's fair share of the shards, and each end up serving half the queue for
 * no reason anybody asked for.
 * <p>
 * Closed with the application context, so the shards this process holds are released on shutdown
 * rather than left to their leases — a rolling restart hands over immediately instead of leaving
 * every shard dark for the lease TTL.
 */
public class ShardOwnedQueueFactory implements MessageQueues, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ShardOwnedQueueFactory.class);

    private final DataSource         dataSource;
    private final ShardRuntime       runtime;
    private final ShardOwnerSettings settings;
    private final String             instanceId;
    /**
     * Applied to every queue this factory builds, in the order the chain sorts them.
     * <p>
     * Collected from the context rather than registered per queue, because an interceptor's usual
     * job — a correlation id, a tenant stamp, a kill switch — is a property of the application, not
     * of one queue. A queue that needs its own can still add it after {@code queue(...)} returns,
     * before it consumes.
     */
    private final List<MessageQueueInterceptor> interceptors;
    private final List<QueueObserver>           observers;

    private final Map<QueueName, PostgresqlMessageQueue> queues = new ConcurrentHashMap<>();

    public ShardOwnedQueueFactory(DataSource dataSource,
                                  ShardRuntime runtime,
                                  ShardOwnerSettings settings,
                                  String instanceId,
                                  ShardOwnedQueueInitializer initializer,
                                  List<MessageQueueInterceptor> interceptors,
                                  List<QueueObserver> observers) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.runtime = requireNonNull(runtime, "No runtime provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.interceptors = List.copyOf(requireNonNull(interceptors, "No interceptors provided"));
        this.observers = List.copyOf(requireNonNull(observers, "No observers provided"));
        // Not stored: depended on so that Spring orders schema creation and queue registration
        // before this bean exists. A factory that could hand out a queue whose tables are not there
        // yet would fail in a way that looks like a queue bug rather than a start-up ordering one.
        requireNonNull(initializer, "No initializer provided");
    }

    /**
     * The queue registered under {@code queueName}.
     *
     * @throws IllegalStateException if the name has not been registered — either list it under
     *                               {@code essentials.shard-owned-queue.queues} or register it
     *                               explicitly. The factory will not invent a shard count.
     */
    public MessageQueue queue(QueueName queueName) {
        requireNonNull(queueName, "No queueName provided");
        return queues.computeIfAbsent(queueName, name -> {
            var queue = PostgresqlMessageQueue.builder()
                                              .setDataSource(dataSource)
                                              .setQueueName(name)
                                              .setInstanceId(instanceId)
                                              .setSettings(settings)
                                              .build();
            // Before anything consumes: both are read when a subscription starts, so one attached
            // afterwards would silently see nothing.
            interceptors.forEach(queue::addInterceptor);
            observers.forEach(queue::addObserver);
            return queue;
        });
    }

    public MessageQueue queue(String queueName) {
        return queue(QueueName.of(queueName));
    }

    /**
     * {@inheritDoc}
     * <p>
     * Read from the registry rather than from {@link #queues}, which holds only the names this
     * process has asked for. An administrative caller listing queues wants what exists, and the two
     * differ by exactly the queues this pod does not consume — which are the ones most worth looking
     * at when something is stuck.
     */
    @Override
    public List<QueueName> queueNames() throws SQLException {
        return ShardOwnedSchema.queueNames(dataSource);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Building a queue does not start consuming from it — {@code consume(...)} does — so inspecting a
     * queue this process does not serve leaves the shard leases where they are. It does add the queue
     * to the cache, which only means it is stopped along with the others on shutdown.
     */
    @Override
    public Optional<MessageQueue> findQueue(QueueName queueName) throws SQLException {
        requireNonNull(queueName, "No queueName provided");
        var cached = queues.get(queueName);
        if (cached != null) {
            return Optional.of(cached);
        }
        return ShardOwnedSchema.resolve(dataSource, queueName)
                               .map(registered -> queue(registered.name()));
    }

    /** The instance identity this process competes for shards under. */
    public String instanceId() {
        return instanceId;
    }

    /** The runtime every queue from this factory shares. */
    public ShardRuntime runtime() {
        return runtime;
    }

    @Override
    public void close() {
        queues.values().forEach(queue -> {
            try {
                queue.stop();
            } catch (RuntimeException e) {
                // Best effort, and one queue's failure must not stop the others being released:
                // whatever is left holding a lease costs its successor the TTL.
                log.warn("Failed to stop a queue on shutdown", e);
            }
        });
        queues.clear();
    }
}
