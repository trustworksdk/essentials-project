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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Drives the shard-owned queue engine with realistic trading traffic on BOTH lanes at once.
 *
 * <h2>Why both lanes, and what each is for</h2>
 * The lanes answer different questions and the demo needs both running together, because in a real
 * application they share a database, a connection pool and a set of pump threads.
 * <ul>
 *     <li><b>Unordered</b> — price ticks. Placement is round-robin and nothing depends on which shard
 *         a tick lands in, so this lane is about throughput and idle cost.</li>
 *     <li><b>Ordered</b> — per-account activity, keyed by account. A key's messages must be handled
 *         one at a time in {@code key_order}, so this lane is about ordering surviving depth,
 *         rebalancing and restart.</li>
 * </ul>
 *
 * <h2>Sustained versus spike</h2>
 * A steady trickle exercises the costs the engine is designed around: whether its query rate follows
 * the work rather than the fan-out. A spike exercises the opposite — a burst that arrives far faster
 * than handlers drain it, so a backlog builds, the cursor falls behind, and per-key ordering has to
 * hold while the queue is genuinely deep. Only the second reproduces the conditions most of the
 * engine's mechanisms exist for, and only the first tells you what it costs when nothing is
 * happening.
 *
 * <h2>What it records</h2>
 * Ordering is checked as messages arrive: each ordered message carries its key's sequence number, and
 * the handler asserts it is greater than the last one seen for that key. A violation is counted
 * rather than thrown, because the point is to observe the property under load, not to fail a demo.
 */
@Component
public class QueueLoadGenerator {

    private static final Logger log = LoggerFactory.getLogger(QueueLoadGenerator.class);

    /** Anything non-zero; the engine treats payload type as opaque application metadata. */
    private static final int PRICE_TICK      = 1;
    private static final int ACCOUNT_ACTIVITY = 2;

    private final QueueLoadGeneratorProperties properties;
    private final ShardOwnedQueueFactory       queues;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong unorderedEnqueued = new AtomicLong();
    private final AtomicLong orderedEnqueued   = new AtomicLong();
    private final AtomicLong unorderedHandled  = new AtomicLong();
    private final AtomicLong orderedHandled    = new AtomicLong();
    private final AtomicLong orderViolations   = new AtomicLong();
    private final AtomicLong spikes            = new AtomicLong();
    private final AtomicReference<Instant> lastSpikeAt = new AtomicReference<>();

    /** Highest sequence handled per key, which is what makes an ordering violation observable here. */
    private final ConcurrentMap<String, Long> highestPerKey = new ConcurrentHashMap<>();
    /** Producer-side sequence per key, so key_order is monotonic per key as the lane requires. */
    private final ConcurrentMap<String, AtomicLong> nextOrderPerKey = new ConcurrentHashMap<>();

    /**
     * Every key this instance produces starts with this, and that is what makes the generator safe to
     * run on more than one instance at a time.
     * <p>
     * {@code nextOrderPerKey} is in-memory and starts at zero in every JVM, so two instances
     * generating into the same key space both number {@code ACC-77} 0, 1, 2 … — which is not a near
     * miss, it is two producers each claiming to be the authority on what order that key's messages
     * are in. The ordered lane's primary key is {@code (queue_id, shard, msg_key, key_order)}, so the
     * second one to arrive at a given position is rejected outright:
     * {@code duplicate key value violates unique constraint "shard_queue_ordered_pkey"}, once per
     * sustained tick, plus an ordering violation for every pair that did get through interleaved.
     * <p>
     * That constraint is the engine being right. {@code key_order} is the PRODUCER's statement of
     * what order means, and a statement needs one author: a real producer gets this for free because
     * a key is an aggregate and an aggregate has one writer at a time. A load generator running in
     * n processes has to arrange it, and the cheapest honest arrangement is to give each process a
     * key space of its own rather than to elect one producer — every instance then exercises both
     * the producing and the consuming side, which is the point of running two.
     */
    private final String keyPrefix;

    /**
     * Its own scheduler, like {@code TradingLoadGeneratorManager}, rather than {@code @Scheduled}.
     * The demo has no {@code @EnableScheduling}, so the annotation is inert here — it binds, it
     * validates, and it never fires, which is the quietest way for a load generator to generate no
     * load.
     */
    private volatile ScheduledExecutorService scheduler;
    private volatile MessageQueue queue;
    private volatile Subscription subscription;

    public QueueLoadGenerator(QueueLoadGeneratorProperties properties, ShardOwnedQueueFactory queues) {
        this.properties = properties;
        this.queues = queues;
        this.keyPrefix = "ACC-" + queues.instanceId() + "-";
        if (properties.isEnabled()) {
            start();
        }
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        try {
            // Registered here rather than listed under essentials.shard-owned-queue.queues, so the
            // name lives in one place instead of having to agree across YAML and this class.
            // Idempotent: every instance may call it, and the first to arrive interns the name.
            queue = queues.register(QueueName.of(properties.getQueueName()), properties.getShardCount());

            // ONE subscription, and that is the contract rather than a simplification.
            //
            // MessageQueue.consume covers BOTH lanes: the lane a message takes is decided by the
            // message — Message.of is unordered, Message.ordered carries a key — not by the
            // consumer. A second consume() on the same queue is therefore not "the other lane", it
            // is a genuinely separate competing consumer with its own instance identity, which
            // halves everyone's fair share. Registering one per lane, as this first did, delivered
            // price ticks to the ordered handler, dead-lettered nineteen hundred of them for failing
            // to parse as a sequence number, and reported two live instances for one process.
            //
            // Which lane a message came from is answered by payloadType, which is what it is for.
            subscription = queue.consume(
                    (messageId, key, payload, payloadType) -> {
                        simulateWork();
                        if (payloadType == ACCOUNT_ACTIVITY) {
                            recordOrdering(key, payload);
                            orderedHandled.incrementAndGet();
                        } else {
                            unorderedHandled.incrementAndGet();
                        }
                    },
                    consumerOptions());

            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable, "queue-load-generator");
                thread.setDaemon(true);
                return thread;
            });
            var intervalMillis = Math.max(1L, properties.getSustainedInterval().toMillis());
            scheduler.scheduleWithFixedDelay(this::sustained, intervalMillis, intervalMillis,
                                             TimeUnit.MILLISECONDS);

            running.set(true);
            log.info("Queue load generator started against queue '{}' — sustained {} msg per lane every {}, "
                     + "spike {} per lane, {} keys under '{}'",
                     properties.getQueueName(), properties.getSustainedBatch(),
                     properties.getSustainedInterval(), properties.getSpikeSize(),
                     properties.getKeyCount(), keyPrefix);
        } catch (Exception e) {
            log.error("Could not start the queue load generator", e);
            throw new IllegalStateException("Could not start the queue load generator", e);
        }
    }

    /**
     * Defaults apart from the handler budget, which is the one knob a demo has any business setting:
     * it decides how far behind the handlers fall during a spike, and therefore whether a spike
     * produces a backlog worth watching.
     */
    private ConsumerOptions consumerOptions() {
        var defaults = ConsumerOptions.defaults();
        return new ConsumerOptions(properties.getParallelConsumers(),
                                   defaults.maxShards(),
                                   defaults.maxAttempts(),
                                   defaults.retryDelay(),
                                   defaults.retryMultiplier(),
                                   defaults.maxRetryDelay());
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        closeQuietly(subscription);
        subscription = null;
        log.info("Queue load generator stopped");
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    /**
     * The sustained arm. Deliberately small and frequent rather than large and rare: this is the
     * shape that tells you what the engine costs when it is merely keeping up.
     */
    public void sustained() {
        if (!running.get()) {
            return;
        }
        try {
            enqueueBatch(properties.getSustainedBatch());
        } catch (Exception e) {
            log.warn("Sustained enqueue failed", e);
        }
    }

    /**
     * The spike arm. One call, everything at once, in a single batch per lane so the burst really is
     * a burst rather than a fast trickle — a five-thousand-message batch is also the size at which
     * enqueue atomicity stops being accidental.
     *
     * @return how many messages were enqueued across both lanes
     */
    public int spike(int size) {
        if (!running.get()) {
            throw new IllegalStateException("The queue load generator is not running");
        }
        var perLane = size > 0 ? size : properties.getSpikeSize();
        try {
            var enqueued = enqueueBatch(perLane);
            spikes.incrementAndGet();
            lastSpikeAt.set(Instant.now());
            log.info("Queue spike: {} messages per lane, {} in total", perLane, enqueued);
            return enqueued;
        } catch (Exception e) {
            throw new IllegalStateException("Spike failed", e);
        }
    }

    /**
     * Allocation and enqueue happen together, and that is a correctness requirement rather than
     * tidiness.
     * <p>
     * {@code key_order} is the PRODUCER's statement of what order means, so the engine can only
     * deliver a key in the order the producer numbered it if the numbering and the committing agree.
     * They did not here: the sustained arm and a spike both number the same keys, and a spike takes
     * seconds to insert fifty thousand rows, so the sustained arm would take a HIGHER key_order and
     * commit it FIRST. That is the producer-side race the ordered lane documents as a real ordering
     * violation, and it produced 7 390 of them in one spike — the engine faithfully reporting a
     * defect in the code feeding it.
     * <p>
     * A real producer gets this for free when a key's messages come from one aggregate under one
     * unit of work. A load generator with two arms has to arrange it.
     */
    private final Object enqueueLock = new Object();

    private int enqueueBatch(int perLane) throws Exception {
        var messages = new ArrayList<Message>(perLane * 2);
        synchronized (enqueueLock) {
        for (var index = 0; index < perLane; index++) {
            // Unordered: a price tick. Nothing depends on where it lands.
            messages.add(Message.of(payload("tick"), PRICE_TICK));

            // Ordered: activity on one account, with a key_order that only ever increases for that
            // key. The engine enforces one-at-a-time per key; the producer still has to number them,
            // because key_order is the producer's statement of what order means.
            var key   = keyPrefix + ThreadLocalRandom.current().nextInt(properties.getKeyCount());
            var order = nextOrderPerKey.computeIfAbsent(key, ignored -> new AtomicLong())
                                       .getAndIncrement();
            messages.add(Message.ordered(payload(Long.toString(order)), ACCOUNT_ACTIVITY, key, order));
        }
        queue.enqueue(messages);
        }
        unorderedEnqueued.addAndGet(perLane);
        orderedEnqueued.addAndGet(perLane);
        return messages.size();
    }

    /**
     * The property the ordered lane sells, checked where it can actually be observed.
     * <p>
     * Counted rather than thrown: a violation is a finding to surface on the dashboard, and throwing
     * here would instead put the message through the engine's retry and dead-letter path, which
     * would obscure the very thing being measured.
     */
    private void recordOrdering(String key, byte[] payload) {
        var order = Long.parseLong(new String(payload, StandardCharsets.UTF_8));
        highestPerKey.merge(key, order, (previous, incoming) -> {
            if (incoming <= previous) {
                orderViolations.incrementAndGet();
                log.warn("Ordering violation on key {}: {} arrived after {}", key, incoming, previous);
                return previous;
            }
            return incoming;
        });
    }

    private void simulateWork() {
        var delay = properties.getHandlerDelay();
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] payload(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Subscription subscription) {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.warn("Could not close a queue subscription cleanly", e);
            }
        }
    }

    public QueueLoadStatus status() {
        var depth  = safely(() -> queue == null ? null : queue.depth());
        var health = safely(() -> queue == null ? null : queue.health());
        return new QueueLoadStatus(running.get(),
                                   properties.getQueueName(),
                                   unorderedEnqueued.get(), unorderedHandled.get(),
                                   orderedEnqueued.get(), orderedHandled.get(),
                                   orderViolations.get(),
                                   spikes.get(), lastSpikeAt.get(),
                                   depth == null ? 0 : depth.unordered(),
                                   depth == null ? 0 : depth.ordered(),
                                   depth == null ? 0 : depth.deadLettered(),
                                   health == null ? 0 : health.unorderedOwned(),
                                   health == null ? 0 : health.orderedOwned(),
                                   health == null ? 0 : health.unownedShards(),
                                   health != null && health.fullyOwned(),
                                   health == null ? 0 : health.liveInstances());
    }

    private static <T> T safely(SupplierThatThrows<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.debug("Could not read queue state", e);
            return null;
        }
    }

    @FunctionalInterface
    private interface SupplierThatThrows<T> {
        T get() throws Exception;
    }

    /**
     * @param unownedShards the number worth alerting on — depth cannot tell "nobody is consuming"
     *                      from "busy", and this can
     */
    public record QueueLoadStatus(boolean running,
                                  String queueName,
                                  long unorderedEnqueued,
                                  long unorderedHandled,
                                  long orderedEnqueued,
                                  long orderedHandled,
                                  long orderViolations,
                                  long spikes,
                                  Instant lastSpikeAt,
                                  long unorderedDepth,
                                  long orderedDepth,
                                  long deadLetteredDepth,
                                  int unorderedShardsOwned,
                                  int orderedUnitsOwned,
                                  int unownedShards,
                                  boolean fullyOwned,
                                  int liveInstances) {
    }
}
