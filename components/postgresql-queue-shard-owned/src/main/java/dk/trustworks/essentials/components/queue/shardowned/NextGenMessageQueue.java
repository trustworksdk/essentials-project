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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The shard-owned engine behind the {@link MessageQueue} contract.
 * <p>
 * Written to check that the contract is implementable rather than merely well-argued. Two things it
 * turned up that the interface alone did not show: enqueue has to split a mixed batch by lane before
 * it can route anything, and a single handler signature has to serve both lanes, which is why
 * {@link MessageHandler} takes a nullable key rather than the two separate shapes the engine uses
 * internally.
 */
public final class NextGenMessageQueue implements MessageQueue {

    private final NextGenStorage storage;
    private final DataSource     dataSource;
    private final short          queueId;
    private final int            shardCount;
    private final String         instanceId;

    private final List<NextGenQueue>  consumers = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final List<QueueObserver> observers = new java.util.concurrent.CopyOnWriteArrayList<>();

    public NextGenMessageQueue(DataSource dataSource, short queueId, int shardCount, String instanceId) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
        this.shardCount = shardCount;
        this.instanceId = requireNonNull(instanceId, "No instanceId provided");
        this.storage = new NextGenStorage(dataSource, queueId);
    }

    @Override
    public MessageQueue addObserver(QueueObserver observer) {
        observers.add(requireNonNull(observer, "No observer provided"));
        return this;
    }

    /**
     * Observers are invoked without catching what they throw. An observer that fails is a bug in the
     * observer, and swallowing it would hide that bug while corrupting the measurement it was
     * installed to produce.
     */
    private void notifyObservers(java.util.function.Consumer<QueueObserver> notification) {
        observers.forEach(notification);
    }

    /**
     * The registered observers as a single one, reading the list live so an observer registered after
     * {@link #consume} still sees events. Handed to the engine, which has no notion of a list.
     */
    private final QueueObserver fanOut = new QueueObserver() {
        @Override
        public void deliveryFailed(String key, int attempt, Throwable cause) {
            notifyObservers(observer -> observer.deliveryFailed(key, attempt, cause));
        }

        @Override
        public void retryScheduled(String key, int attempt, long delayMillis) {
            notifyObservers(observer -> observer.retryScheduled(key, attempt, delayMillis));
        }

        @Override
        public void deadLettered(MessageId id, int attempts, Throwable cause) {
            notifyObservers(observer -> observer.deadLettered(id, attempts, cause));
        }

        @Override
        public void shardOwnershipChanged(int shard, boolean acquired) {
            notifyObservers(observer -> observer.shardOwnershipChanged(shard, acquired));
        }
    };

    @Override
    public List<MessageId> enqueue(List<Message> messages) throws SQLException {
        requireNonNull(messages, "No messages provided");
        if (messages.isEmpty()) {
            return List.of();
        }
        // A batch may mix lanes, and the lanes are different tables with different keys — so the
        // split has to happen before anything can be routed. The interface hides that from callers,
        // which is the right place for it to be hidden.
        var unordered = messages.stream().filter(message -> !message.isOrdered()).toList();
        var ordered = messages.stream().filter(Message::isOrdered).toList();

        var ids = new ArrayList<MessageId>(messages.size());
        if (!unordered.isEmpty()) {
            var shard = Math.floorMod(Objects.hash(instanceId, System.nanoTime()), shardCount);
            try (var connection = dataSource.getConnection()) {
                var seqs = storage.enqueuePreClaimed(connection, shard,
                                                     unordered.stream().map(Message::payload).toList(),
                                                     unordered.getFirst().payloadType(),
                                                     -1L);
                seqs.forEach(seq -> ids.add(new MessageId(MessageId.Lane.UNORDERED, shard, seq)));
            }
        }
        if (!ordered.isEmpty()) {
            var byShard = new HashMap<Integer, List<NextGenStorage.OrderedPayload>>();
            for (var message : ordered) {
                byShard.computeIfAbsent(NextGenSchema.shardForKey(message.key(), shardCount), s -> new ArrayList<>())
                       .add(new NextGenStorage.OrderedPayload(message.key(), message.keyOrder(), message.payload()));
            }
            try (var connection = dataSource.getConnection()) {
                for (var entry : byShard.entrySet()) {
                    storage.enqueueOrderedBatch(connection, entry.getKey(), entry.getValue(),
                                                ordered.getFirst().payloadType());
                    entry.getValue().forEach(payload ->
                            ids.add(new MessageId(MessageId.Lane.ORDERED, entry.getKey(), payload.keyOrder())));
                }
            }
        }
        if (!unordered.isEmpty()) {
            notifyObservers(observer -> observer.enqueued(unordered.size(), false));
        }
        if (!ordered.isEmpty()) {
            notifyObservers(observer -> observer.enqueued(ordered.size(), true));
        }
        return ids;
    }

    @Override
    public Subscription consume(MessageHandler handler, ConsumerOptions options) throws SQLException {
        requireNonNull(handler, "No handler provided");
        requireNonNull(options, "No options provided");

        var policy = new RedeliveryPolicy(options.maxAttempts(), options.retryDelay(),
                                          options.retryMultiplier(), options.maxRetryDelay());
        var settings = ShardOwnerSettings.defaults();

        // The engine reports retries, dead letters and shard ownership from the places that know the
        // attempt count and the fence; handing it the observer fan-out is what makes those reachable.
        var engineMetrics = new ShardOwnerMetrics(fanOut);
        var unorderedConsumer = new NextGenQueue(dataSource, queueId, shardCount, instanceId + "-u", engineMetrics);
        unorderedConsumer.setParallelConsumers(options.parallelConsumers());
        unorderedConsumer.startConsuming(payload -> invoke(handler, null, payload),
                                         settings, options.maxShards(), policy);
        var orderedConsumer = new NextGenQueue(dataSource, queueId, shardCount, instanceId + "-o", engineMetrics);
        orderedConsumer.setParallelConsumers(options.parallelConsumers());
        orderedConsumer.startConsumingOrdered((key, payload) -> invoke(handler, key, payload),
                                              settings, options.maxShards(), policy);
        synchronized (consumers) {
            consumers.add(unorderedConsumer);
            consumers.add(orderedConsumer);
        }

        return new Subscription() {
            @Override
            public int shardsHeld() {
                return unorderedConsumer.shardsHeld();
            }

            @Override
            public void start() {
                unorderedConsumer.start();
                orderedConsumer.start();
            }

            @Override
            public void stop() {
                unorderedConsumer.stop();
                orderedConsumer.stop();
            }

            @Override
            public boolean isStarted() {
                return unorderedConsumer.isStarted() || orderedConsumer.isStarted();
            }
        };
    }

    /**
     * Adapt a checked-exception handler to the engine's unchecked contract. A handler that throws a
     * checked exception means the same thing as one that throws unchecked — the message failed — and
     * the redelivery policy should not care which.
     */
    private void invoke(MessageHandler handler, String key, byte[] payload) {
        var startNanos = System.nanoTime();
        var failure = new Throwable[1];
        Runnable delivery = () -> {
            try {
                handler.handle(key, payload);
            } catch (RuntimeException e) {
                failure[0] = e;
                throw e;
            } catch (Exception e) {
                failure[0] = e;
                throw new IllegalStateException(e);
            }
        };
        try {
            // Wrapped rather than notified-after, because a tracing span has to enclose the work. The
            // observers wrap in registration order, innermost last.
            var wrapped = delivery;
            for (var observer : observers) {
                var inner = wrapped;
                wrapped = () -> observer.aroundDelivery(key, inner);
            }
            wrapped.run();
            var duration = System.nanoTime() - startNanos;
            notifyObservers(observer -> observer.delivered(key, duration));
        } catch (RuntimeException e) {
            // deliveryFailed is NOT reported here. This frame does not know which attempt this was,
            // and the earlier version said "attempt 0" for every failure — a number that was always
            // wrong. The owners emit it, from the one place that counts attempts.
            throw e;
        }
    }

    @Override
    public QueueSession openSession(SessionScope scope, Duration leaseDuration) throws SQLException {
        requireNonNull(scope, "No scope provided");
        requireNonNull(leaseDuration, "No leaseDuration provided");
        return switch (scope) {
            case SHARD -> new ShardQueueSession(storage, dataSource, instanceId + "-session-" + UUID.randomUUID(),
                                                shardCount, shardCount, leaseDuration);
            case MESSAGE, BATCH -> new RowLeaseQueueSession(storage, dataSource, shardCount, scope, leaseDuration);
            // KEY scope is specified as "ordering safe, blocks only that key". In this engine it is
            // not implementable at that description, and the reason is the design's central claim
            // rather than a missing piece: per-key order is enforced by an in-memory set of the keys
            // the shard's owner currently has in flight. A session elsewhere cannot enter that set.
            // For it to take one key safely, the owner would have to re-check the database before
            // dispatching each key — a query per message on the fast path, which is precisely the
            // cost the ordered lane is built to avoid paying.
            //
            // Widening to SHARD is not a workaround, it is the honest answer: on the ordered lane the
            // unit of exclusivity IS the shard, because that is what makes ordering free.
            case KEY -> throw new UnsupportedOperationException(
                    "KEY scope cannot be honoured on the ordered lane without adding a per-dispatch "
                    + "query to the owner's fast path, which is the cost ordering-by-ownership exists "
                    + "to avoid. Use SHARD scope, which is exclusive at the level this engine orders at");
        };
    }

    @Override
    public QueueDepth depth() throws SQLException {
        var unordered = 0L;
        var ordered = 0L;
        for (var shard = 0; shard < shardCount; shard++) {
            unordered += storage.countRemaining(shard);
            ordered += storage.countOrderedRemaining(shard);
        }
        return new QueueDepth(unordered, ordered, storage.countDeadLetters());
    }

    @Override
    public List<DeadLetter> deadLetters(int offset, int limit) throws SQLException {
        return storage.deadLetters(offset, limit).stream()
                      .map(row -> new DeadLetter(new MessageId("ordered".equals(row.lane())
                                                               ? MessageId.Lane.ORDERED
                                                               : MessageId.Lane.UNORDERED,
                                                               row.shard(), row.seq()),
                                                 row.key(), row.payload(), 0, row.attempts(), row.error()))
                      .toList();
    }

    @Override
    public boolean resurrect(MessageId messageId) throws SQLException {
        requireNonNull(messageId, "No messageId provided");
        return storage.resurrect(messageId.shard(), messageId.sequence(),
                                 messageId.lane() == MessageId.Lane.ORDERED ? "ordered" : "unordered");
    }

    @Override
    public long purge() throws SQLException {
        return storage.purge();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Restarts consumers this queue created and then stopped. A queue that has never had a consumer
     * has nothing to start, and enqueueing and reading depth never required it — matching
     * {@code DurableQueues}, where the lifecycle governs the consuming side.
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        synchronized (consumers) {
            consumers.forEach(NextGenQueue::start);
        }
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        synchronized (consumers) {
            consumers.forEach(NextGenQueue::stop);
            consumers.clear();
        }
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }
}
