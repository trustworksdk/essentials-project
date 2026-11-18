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
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
public class ShardOwnedQueueFactory implements MessageQueues, AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
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
    /** Called once per queue, for the per-queue meters a shared observer cannot give. */
    private final List<ShardOwnedQueueMetrics>  metrics;

    private final Map<QueueName, PostgresqlMessageQueue> queues = new ConcurrentHashMap<>();
    /**
     * The settings of the queues given their own {@code stuckHandlerTimeout}, by name ({@link #setStuckHandlerTimeouts}):
     * {@link #settings} with that one value replaced. Every other queue takes {@link #settings}.
     */
    private final Map<QueueName, ShardOwnerSettings>     ownSettings          = new ConcurrentHashMap<>();
    /** The names in {@link #ownSettings} this factory has built a queue under. */
    private final Set<QueueName>                         ownSettingsBuilt     = ConcurrentHashMap.newKeySet();
    /** Set once {@link #onApplicationEvent} has reported the names in {@link #ownSettings} no queue was built under. */
    private final AtomicBoolean                          ownSettingsReported  = new AtomicBoolean();

    public ShardOwnedQueueFactory(DataSource dataSource,
                                  ShardRuntime runtime,
                                  ShardOwnerSettings settings,
                                  String instanceId,
                                  ShardOwnedQueueInitializer initializer,
                                  List<MessageQueueInterceptor> interceptors,
                                  List<QueueObserver> observers,
                                  List<ShardOwnedQueueMetrics> metrics) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.runtime = requireNonNull(runtime, "No runtime provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.interceptors = List.copyOf(requireNonNull(interceptors, "No interceptors provided"));
        this.observers = List.copyOf(requireNonNull(observers, "No observers provided"));
        this.metrics = List.copyOf(requireNonNull(metrics, "No metrics provided"));
        // Not stored: depended on so that Spring orders schema creation and queue registration
        // before this bean exists. A factory that could hand out a queue whose tables are not there
        // yet would fail in a way that looks like a queue bug rather than a start-up ordering one.
        requireNonNull(initializer, "No initializer provided");
    }

    /**
     * Give the queues named here their own {@code stuckHandlerTimeout} ({@link ShardOwnerSettings}), every other setting
     * shared: what {@code essentials.shard-owned-queue.stuck-handler-timeouts} configures. Call it before the first
     * {@link #queue} of a queue named here: a queue already built keeps the settings it was built with. A name absent here
     * takes the {@code stuckHandlerTimeout} of the settings this factory was given.
     * <p>
     * A name matches a queue only when it equals the queue's name exactly, case included. Building a queue under a name
     * given here logs that queue's bound at INFO; once the application is ready, one WARN names every name given here that
     * no queue had been built under by then ({@link #onApplicationEvent}).
     *
     * @param perQueue queue name to its bound; each bound positive
     * @return this factory
     */
    public ShardOwnedQueueFactory setStuckHandlerTimeouts(Map<String, Duration> perQueue) {
        requireNonNull(perQueue, "No perQueue provided");
        perQueue.forEach((name, timeout) -> {
            // Built here, so a wrong value fails the context rather than the first queue() of that name.
            ownSettings.put(QueueName.of(name),
                            settings.withStuckHandlerTimeout(requireNonNull(timeout, "No stuckHandlerTimeout provided for queue '" + name + "'")));
        });
        return this;
    }

    /**
     * The settings the queue named {@code queueName} is built with: this factory's, with that queue's own
     * {@code stuckHandlerTimeout} when {@link #setStuckHandlerTimeouts} named it.
     */
    ShardOwnerSettings settingsFor(QueueName queueName) {
        return ownSettings.getOrDefault(queueName, settings);
    }

    /**
     * The names {@link #setStuckHandlerTimeouts} was given that this factory has not built a queue under, in name order.
     */
    List<QueueName> stuckHandlerTimeoutsNoQueueIsBuiltUnder() {
        return ownSettings.keySet().stream()
                          .filter(name -> !ownSettingsBuilt.contains(name))
                          .sorted()
                          .toList();
    }

    /**
     * Once the application is ready, WARNs - once - naming every name {@link #setStuckHandlerTimeouts} was given that no
     * queue had been built under by then: a typo, or other casing (an environment variable's map key arrives in lower
     * case), leaves the queue it meant on the shared {@code stuckHandlerTimeout}, and nothing else would show it. A queue
     * built later under such a name still takes its own bound.
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        var notBuilt = stuckHandlerTimeoutsNoQueueIsBuiltUnder();
        if (!notBuilt.isEmpty() && ownSettingsReported.compareAndSet(false, true)) {
            log.warn("essentials.shard-owned-queue.stuck-handler-timeouts names {} that no queue had been built under by the time the " +
                     "application was ready: {}. A name must equal the queue's name exactly, case included; a queue built under another " +
                     "name takes the shared stuckHandlerTimeout {}. A queue built later under one of these names still takes its own.",
                     notBuilt.size() == 1 ? "1 name" : notBuilt.size() + " names", notBuilt, settings.stuckHandlerTimeout());
        }
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
            var queueSettings = settingsFor(name);
            var queue = PostgresqlMessageQueue.builder()
                                              .setDataSource(dataSource)
                                              .setQueueName(name)
                                              .setInstanceId(instanceId)
                                              .setSettings(queueSettings)
                                              // The process's one runtime. Without it every queue's
                                              // consumers borrowed a second, shared runtime, and the
                                              // bean's pumps and listener held their connections for
                                              // nothing.
                                              .setRuntime(runtime)
                                              .build();
            // After build(), which can throw (a name not registered): only a queue actually built counts as built.
            if (ownSettings.containsKey(name)) {
                ownSettingsBuilt.add(name);
                // So an operator can see the override took: its absence is the only sign of a name that did not match.
                log.info("Queue '{}' built with its own stuckHandlerTimeout {} (essentials.shard-owned-queue.stuck-handler-timeouts); " +
                         "every other setting is shared", name, queueSettings.stuckHandlerTimeout());
            }
            // Before anything consumes: both are read when a subscription starts, so one attached
            // afterwards would silently see nothing.
            interceptors.forEach(queue::addInterceptor);
            observers.forEach(queue::addObserver);
            metrics.forEach(binding -> binding.bind(name, queue));
            return queue;
        });
    }

    /**
     * Register a queue and return it, for a component that owns its own queue.
     * <p>
     * The alternative is listing the name under {@code essentials.shard-owned-queue.queues}, which
     * puts it in two places — the YAML and the code that consumes it — that have to agree, and whose
     * disagreement is a start-up failure. A component that knows its queue name can say so once,
     * here.
     * <p>
     * Idempotent, and safe to call from every instance at start-up: whichever gets there first
     * interns the name and the rest resolve it. Re-registering with a <em>different</em> shard count
     * is refused rather than accepted, because two processes disagreeing about it write to shards
     * nobody leases.
     * <p>
     * This does not make the engine auto-registering. An unknown name still fails in
     * {@link #queue(QueueName)}: the count caps how many instances can consume the unordered lane, and
     * an SPI caller writes the name in its own source, so a typo quietly becoming a queue is worse
     * than an error. The {@code DurableQueues} adapter is the opposite case and registers by default,
     * because the names there are the framework's and not knowable up front.
     *
     * @param shardCount the UNORDERED lane's shard count. The ordered lane routes over the space
     *                   recorded on the registry row; use
     *                   {@link #register(QueueName, int, int)} to choose it.
     */
    public MessageQueue register(QueueName queueName, int shardCount) throws SQLException {
        return register(queueName, shardCount, ShardOwnedSchema.ORDERED_UNITS);
    }

    /** {@link #register(QueueName, int)}, choosing the ordered lane's routing space as well. */
    public MessageQueue register(QueueName queueName, int shardCount, int orderedUnits) throws SQLException {
        requireNonNull(queueName, "No queueName provided");
        ShardOwnedSchema.registerQueue(dataSource, queueName, shardCount, orderedUnits);
        return queue(queueName);
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
    public Optional<Duration> leaseTtl() {
        return Optional.of(settings.leaseTtl());
    }

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
