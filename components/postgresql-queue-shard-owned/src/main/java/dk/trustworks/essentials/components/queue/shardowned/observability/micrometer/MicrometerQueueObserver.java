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

package dk.trustworks.essentials.components.queue.shardowned.observability.micrometer;

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Micrometer binding for {@link QueueObserver}.
 * <p>
 * The engine module declares {@code micrometer-core} as {@code provided}, per the project-wide rule
 * that third-party integrations are not transitive: a consumer that wants these meters already has
 * Micrometer on its own classpath, and one that does not never loads this class.
 * <p>
 * <b>Counters and a timer, not gauges — except where a gauge is asked for.</b> Every meter below is
 * fed by an event the engine already emits, so recording costs nothing beyond the increment. Queue
 * depth is different: it is a query, not an event, and Micrometer polls a gauge on every scrape. A
 * naive depth gauge would put {@code 2 x shardCount + 1} queries on the database per scrape per
 * process. So depth is opt-in through {@link #bindQueueDepth}, and behind a cache whose age the
 * caller chooses.
 * <p>
 * <b>What the tags deliberately do not include.</b> There is no tag for the ordering key. Keys are
 * unbounded — one per customer, per order, per aggregate — and a tag value per key is the classic way
 * to take a metrics backend down. The key reaches {@link #aroundDelivery} for tracing, where it
 * belongs, and never becomes a tag.
 */
public final class MicrometerQueueObserver implements QueueObserver {

    public static final String ENQUEUED_COUNTER     = "essentials.queue.enqueued";
    public static final String DELIVERY_TIMER       = "essentials.queue.delivery";
    public static final String FAILURES_COUNTER     = "essentials.queue.delivery.failures";
    public static final String RETRIES_COUNTER      = "essentials.queue.retries";
    public static final String DEAD_LETTER_COUNTER  = "essentials.queue.deadletters";
    public static final String OWNERSHIP_COUNTER    = "essentials.queue.shard.ownership";
    public static final String DEPTH_GAUGE          = "essentials.queue.depth";
    /**
     * Shards of this queue with a live owner, tagged by lane.
     */
    public static final String SHARDS_OWNED_GAUGE   = "essentials.queue.shards.owned";
    /**
     * Shards, across both lanes, that no live instance is reading. <b>The one to alert on.</b> Zero in
     * steady state, briefly non-zero while shards move, persistently non-zero when messages are
     * sitting in shards nobody reads.
     */
    public static final String SHARDS_UNOWNED_GAUGE = "essentials.queue.shards.unowned";
    /**
     * Instances heartbeating for this queue. Below the number of running processes means colliding ids.
     */
    public static final String INSTANCES_GAUGE      = "essentials.queue.instances";

    public static final String LANE_TAG   = "lane";
    public static final String CHANGE_TAG = "change";
    public static final String QUEUE_TAG  = "queue";

    private final MeterRegistry registry;
    private final List<Tag>     commonTags;

    private final Counter enqueuedUnordered;
    private final Counter enqueuedOrdered;
    private final Timer   deliveryTimer;
    private final Counter failures;
    private final Counter retries;
    private final Counter deadLetters;
    private final Counter shardsAcquired;
    private final Counter shardsReleased;

    public MicrometerQueueObserver(MeterRegistry registry) {
        this(registry, List.of());
    }

    /**
     * Tag every meter with the queue's name.
     * <p>
     * The overload exists so the tag comes from the same {@link QueueName} the engine was built with
     * rather than from a string retyped at the call site — a metrics tag that disagrees with the
     * queue it describes is worse than no tag, because a dashboard cannot tell.
     */
    public MicrometerQueueObserver(MeterRegistry registry, QueueName queueName) {
        this(registry, List.of(Tag.of(QUEUE_TAG, requireNonNull(queueName, "No queueName provided").value())));
    }

    /**
     * @param commonTags applied to every meter — typically the queue's name and the module, so several
     *                   queues in one process stay distinguishable
     */
    public MicrometerQueueObserver(MeterRegistry registry, List<Tag> commonTags) {
        this.registry = requireNonNull(registry, "No registry provided");
        this.commonTags = List.copyOf(requireNonNull(commonTags, "No commonTags provided"));

        // Registered eagerly rather than on first use. A counter that appears only once something has
        // gone wrong is a counter no dashboard can alert on, because the series does not exist until
        // the incident has already started.
        this.enqueuedUnordered = counter(ENQUEUED_COUNTER, "messages enqueued", Tag.of(LANE_TAG, "unordered"));
        this.enqueuedOrdered = counter(ENQUEUED_COUNTER, "messages enqueued", Tag.of(LANE_TAG, "ordered"));
        this.failures = counter(FAILURES_COUNTER, "handler invocations that threw");
        this.retries = counter(RETRIES_COUNTER, "redeliveries scheduled after a failure");
        this.deadLetters = counter(DEAD_LETTER_COUNTER, "messages parked after exhausting their attempts");
        this.shardsAcquired = counter(OWNERSHIP_COUNTER, "shard ownership changes", Tag.of(CHANGE_TAG, "acquired"));
        this.shardsReleased = counter(OWNERSHIP_COUNTER, "shard ownership changes", Tag.of(CHANGE_TAG, "released"));
        this.deliveryTimer = Timer.builder(DELIVERY_TIMER)
                                  .description("time from dispatch to a handler returning successfully")
                                  .tags(this.commonTags)
                                  .publishPercentileHistogram()
                                  .register(registry);
    }

    private Counter counter(String name, String description, Tag... tags) {
        var all = new ArrayList<>(commonTags);
        all.addAll(Arrays.asList(tags));
        return Counter.builder(name).description(description).tags(all).register(registry);
    }

    @Override
    public void enqueued(int messageCount, boolean ordered) {
        (ordered ? enqueuedOrdered : enqueuedUnordered).increment(messageCount);
    }

    @Override
    public void delivered(String key, long durationNanos) {
        deliveryTimer.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void deliveryFailed(String key, int attempt, Throwable cause) {
        failures.increment();
    }

    @Override
    public void retryScheduled(String key, int attempt, long delayMillis) {
        retries.increment();
    }

    @Override
    public void deadLettered(MessageId id, int attempts, Throwable cause) {
        deadLetters.increment();
    }

    @Override
    public void shardOwnershipChanged(int shard, boolean acquired) {
        (acquired ? shardsAcquired : shardsReleased).increment();
    }

    /**
     * Register depth gauges for {@code queue}, refreshed at most once per {@code maxAge}.
     * <p>
     * Separate from the constructor because it is the one part of this binding that costs database
     * work, and because it needs the queue itself rather than only its events. The cache is what makes
     * it safe to scrape often: several scrapes inside one {@code maxAge} share a single query round,
     * and a scrape that arrives while the database is unreachable reports the last known depth rather
     * than throwing into the registry's scrape loop.
     *
     * @return this, so it can be chained onto construction
     */
    public MicrometerQueueObserver bindQueueDepth(MessageQueue queue, Duration maxAge) {
        requireNonNull(queue, "No queue provided");
        requireNonNull(maxAge, "No maxAge provided");
        var snapshot = new CachedDepth(queue, maxAge.toNanos());
        gauge("unordered", snapshot, depth -> depth.unordered());
        gauge("ordered", snapshot, depth -> depth.ordered());
        gauge("dead-letter", snapshot, depth -> depth.deadLettered());
        return this;
    }

    /**
     * The {@code Supplier} form, not {@code Gauge.builder(name, stateObject, fn)}.
     * <p>
     * The object form holds a <b>weak</b> reference to the state, so a cache created inside a bind
     * method is collected as soon as nothing else refers to it and every gauge over it reports
     * {@code NaN} from then on — silently, at an arbitrary later moment. Retaining it on this observer
     * is not enough either, since an observer that is itself only referenced by the registry goes the
     * same way. A supplier is held strongly, so the gauge lives as long as the meter does and the
     * question of who retains what stops mattering.
     */
    private void gauge(String lane, CachedDepth snapshot, java.util.function.ToLongFunction<QueueDepth> extractor) {
        var tags = new ArrayList<>(commonTags);
        tags.add(Tag.of(LANE_TAG, lane));
        Gauge.builder(DEPTH_GAUGE, () -> extractor.applyAsLong(snapshot.get()))
             .description("messages waiting to be handled")
             .tags(tags)
             .register(registry);
    }

    /**
     * Publish whether the queue is being served: owned shards per lane, unowned shards across both,
     * and live instances.
     * <p>
     * Opt-in and cached for the same reason as depth — these are queries and Micrometer polls a gauge
     * on every scrape. Unlike depth they are cheap ones, against the lease and membership tables,
     * which hold a handful of rows per queue.
     * <p>
     * <b>{@link #SHARDS_UNOWNED_GAUGE} is the one worth an alert.</b> Depth cannot tell a queue nobody
     * is consuming from a queue that is merely busy; this can. Every ownership failure this engine has
     * had — a fair share computed against departed instances, two processes disagreeing about the
     * shard count, a consumer shedding shards to nobody — showed up here first and in nothing else.
     *
     * @return this, so it can be chained onto construction
     */
    public MicrometerQueueObserver bindQueueHealth(MessageQueue queue, Duration maxAge) {
        requireNonNull(queue, "No queue provided");
        requireNonNull(maxAge, "No maxAge provided");
        var snapshot = new CachedHealth(queue, maxAge.toNanos());
        // Supplier form throughout, for the reason spelled out on the depth gauge helper.
        Gauge.builder(SHARDS_OWNED_GAUGE, () -> snapshot.get().unorderedOwned())
             .description("shards with a live owner")
             .tags(withCommonTags(Tag.of(LANE_TAG, "unordered")))
             .register(registry);
        Gauge.builder(SHARDS_OWNED_GAUGE, () -> snapshot.get().orderedOwned())
             .description("shards with a live owner")
             .tags(withCommonTags(Tag.of(LANE_TAG, "ordered")))
             .register(registry);
        Gauge.builder(SHARDS_UNOWNED_GAUGE, () -> snapshot.get().unownedShards())
             .description("shards no live instance is reading")
             .tags(withCommonTags())
             .register(registry);
        Gauge.builder(INSTANCES_GAUGE, () -> snapshot.get().liveInstances())
             .description("instances heartbeating for this queue")
             .tags(withCommonTags())
             .register(registry);
        return this;
    }

    private List<Tag> withCommonTags(Tag... extra) {
        var all = new ArrayList<>(commonTags);
        all.addAll(List.of(extra));
        return all;
    }

    /**
     * Same shape as {@link CachedDepth}, and for the same reasons: a scrape must not put a query on
     * the database per meter, and a scrape while the database is unreachable must report the last
     * known value rather than throw into the registry's scrape loop and take every other meter with
     * it.
     */
    private static final class CachedHealth {
        private final    MessageQueue                 queue;
        private final    long                         maxAgeNanos;
        private final    AtomicReference<QueueHealth> value  = new AtomicReference<>(new QueueHealth(0, 0, 0, 0, 0));
        private final    AtomicLong                   readAt = new AtomicLong();
        /**
         * Explicit, for the overflow reason spelled out on {@link CachedDepth}.
         */
        private volatile boolean                      loaded;

        private CachedHealth(MessageQueue queue, long maxAgeNanos) {
            this.queue = queue;
            this.maxAgeNanos = maxAgeNanos;
        }

        private QueueHealth get() {
            var now = System.nanoTime();
            if (loaded && now - readAt.get() < maxAgeNanos) {
                return value.get();
            }
            try {
                var health = queue.health();
                value.set(health);
                readAt.set(now);
                loaded = true;
                return health;
            } catch (SQLException e) {
                // Report the last known value rather than failing the scrape.
                return value.get();
            }
        }
    }

    /**
     * Holds the last depth read and the instant it was taken. Reading is lock-free on the hot path and
     * single-flighted on refresh, so a burst of scrapes issues one query rather than one each.
     */
    private static final class CachedDepth {
        private final    MessageQueue                queue;
        private final    long                        maxAgeNanos;
        private final    AtomicReference<QueueDepth> value      = new AtomicReference<>(new QueueDepth(0, 0, 0, null));
        private final    AtomicLong                  readAt     = new AtomicLong();
        private final    AtomicBoolean               refreshing = new AtomicBoolean();
        /**
         * Explicit rather than a sentinel in {@link #readAt}. {@code System.nanoTime()} may be
         * negative, so seeding the timestamp with {@code Long.MIN_VALUE} and subtracting it overflows;
         * the staleness test then reads as fresh and the gauge reports its seed of zero forever.
         */
        private volatile boolean                     loaded;

        private CachedDepth(MessageQueue queue, long maxAgeNanos) {
            this.queue = queue;
            this.maxAgeNanos = maxAgeNanos;
        }

        private QueueDepth get() {
            var now = System.nanoTime();
            if ((!loaded || now - readAt.get() >= maxAgeNanos) && refreshing.compareAndSet(false, true)) {
                try {
                    value.set(queue.depth());
                    readAt.set(now);
                    loaded = true;
                } catch (SQLException e) {
                    // Report the last known depth. Throwing here would propagate into the registry's
                    // scrape and take out every other meter in the process along with this one.
                    readAt.set(now);
                    loaded = true;
                } finally {
                    refreshing.set(false);
                }
            }
            return value.get();
        }
    }
}
