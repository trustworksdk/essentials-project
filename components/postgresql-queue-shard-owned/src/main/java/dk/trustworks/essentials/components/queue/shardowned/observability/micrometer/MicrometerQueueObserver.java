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

import java.lang.ref.WeakReference;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
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
 * caller chooses. The one gauge registered here regardless, {@link #SHARDS_AWAITING_TAKEOVER_GAUGE}, is
 * fed by events too (a stall starting and ending), so a scrape reads a number in memory, not the database.
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
     * Shards, across both lanes, that this instance holds a lease on but does not serve, because their
     * takeover keeps failing. <b>Alert on it above zero, next to {@link #SHARDS_UNOWNED_GAUGE}</b>, which
     * cannot see these: they are leased. Fed by {@link #takeoverStalled}, so unlike the depth and health
     * gauges it costs no query and is always registered. Per process: sum it across instances.
     * <p>
     * It counts only the stalls an observer saw start. An observer registered while a shard is already
     * stalled reads that shard only from its next stall; the end of the one it missed does not take the
     * gauge below zero, nor end a stall of another shard, or one another observer reported. Stalls are
     * told apart by lane and shard, which is all the callback carries: one observer attached to two queues
     * counts both queues' stalls of the same shard, but the end of one it missed on one queue can still end
     * the same shard's stall it saw on the other.
     * <p>
     * Every observer on one registry with the same tags adds into this one gauge, whichever was registered
     * first: an observer rebuilt for a re-created queue, or one bound by hand next to the starter's, publishes
     * its stalls here too, and a retired one keeps only the stalls it was told started and not yet told ended.
     * So give each queue's observer its queue tag (the Spring starter does); two queues sharing one tag set
     * publish the sum of their counts, as their counters do.
     */
    public static final String SHARDS_AWAITING_TAKEOVER_GAUGE = "essentials.queue.shards.awaiting.takeover";
    /**
     * Instances heartbeating for this queue. Below the number of running processes means colliding ids.
     */
    public static final String INSTANCES_GAUGE      = "essentials.queue.instances";
    /**
     * Ordered keys stopped behind a dead letter right now. A separate meter rather than a lane of
     * {@link #DEPTH_GAUGE}, because it counts keys, not messages.
     */
    public static final String BLOCKED_KEYS_GAUGE   = "essentials.queue.keys.blocked";
    /**
     * Dead letters parked unhandled behind another on their key, right now. Separate from the
     * {@code dead-letter} lane of {@link #DEPTH_GAUGE} because they are already counted in it: summing
     * depth across lanes must not count them twice.
     */
    public static final String PARKED_GAUGE         = "essentials.queue.deadletters.parked";

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
    /**
     * The count behind each registered {@link #SHARDS_AWAITING_TAKEOVER_GAUGE} gauge, keyed by its registry and
     * then by the gauge {@code register} returned. Micrometer hands every observer with the same registry and
     * (post-filter) tags the gauge the first one registered, and that gauge reads only the first one's supplier;
     * so the count lives here, one per gauge, and every observer that gets the gauge adds into it. Keying by the
     * returned gauge rather than by tags gets tag order, registry common tags and meter filters right without
     * restating Micrometer's rules.
     * <p>
     * Registry first, because a meter's {@code equals} compares ids alone: gauges with the same name and tags in
     * two registries are equal keys, and one flat map let an observer on one registry replace the entry of the
     * other's gauge, leaving a later observer there with a count its gauge never reads. {@code MeterRegistry}
     * does not override {@code equals}, so the outer map tells registries apart by identity.
     * <p>
     * Weak keys at both levels: an entry lasts as long as its registry, and within it as long as its gauge is
     * registered. Neither a gauge nor its count refers back to the registry, so the inner map does not keep its
     * own key alive. Guarded by itself, so registering and recording the count is one step for concurrent
     * constructors.
     */
    private static final Map<MeterRegistry, Map<Gauge, SharedStallCount>> AWAITING_TAKEOVER_COUNTS = new WeakHashMap<>();

    /**
     * The value in {@link #AWAITING_TAKEOVER_COUNTS}' per-registry map. It names its gauge by identity because a
     * meter's {@code equals} compares ids: a gauge removed from the registry and registered again is a new gauge
     * reading a new count, which an equal key alone would not tell apart. A weak reference, or the value would
     * hold its own key and the entry would never go.
     */
    private record SharedStallCount(WeakReference<Gauge> gauge, AtomicInteger count) {
    }

    /**
     * Shards whose takeover is failing now, summed over every observer publishing into this observer's
     * {@link #SHARDS_AWAITING_TAKEOVER_GAUGE} gauge. Held by the gauge's supplier, which is a strong reference.
     */
    private final AtomicInteger shardsAwaitingTakeover;
    /**
     * This observer's share of {@link #shardsAwaitingTakeover}: the stalls it was told started and not
     * yet told ended, counted per lane and shard. An end is taken off the shared count only when this
     * observer saw that same shard's stall start, so an observer that missed a stall's start cannot end a
     * stall another observer reported, nor one of another shard it reported itself. A count rather than a
     * set, because one observer attached to two queues is told about each queue's shard 3. An entry goes
     * when its count reaches zero.
     */
    private final ConcurrentMap<Stall, Integer> stallsSeenStarting = new ConcurrentHashMap<>();

    /**
     * A stalled shard as {@link #takeoverStalled} names it: shard numbers repeat across the two lanes.
     */
    private record Stall(MessageId.Lane lane, int shard) {
    }

    /**
     * No tags. Fine for one queue per {@code registry}; with more, prefer {@link #MicrometerQueueObserver(MeterRegistry, QueueName)}.
     * Untagged, every queue's counters, timer and {@link #SHARDS_AWAITING_TAKEOVER_GAUGE} gauge add into one
     * series, so a dashboard cannot tell which queue a stalled takeover or a failure belongs to.
     */
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
        this.shardsAwaitingTakeover = sharedAwaitingTakeoverCount(registry, this.commonTags);
    }

    /**
     * Register the {@link #SHARDS_AWAITING_TAKEOVER_GAUGE} gauge and return the count it reads: a fresh one
     * when this call registered the gauge, the one already behind it when another observer did.
     */
    private static AtomicInteger sharedAwaitingTakeoverCount(MeterRegistry registry, List<Tag> tags) {
        synchronized (AWAITING_TAKEOVER_COUNTS) {
            var candidate = new AtomicInteger();
            // Supplier form, for the reason spelled out on the depth gauge helper. It captures the count only,
            // not this observer, so a retired observer is not kept alive by the registry.
            var gauge = Gauge.builder(SHARDS_AWAITING_TAKEOVER_GAUGE, candidate::get)
                             .description("shards leased by this instance but not served, because their takeover keeps failing")
                             .tags(tags)
                             .register(registry);
            var countsInRegistry = AWAITING_TAKEOVER_COUNTS.computeIfAbsent(registry, r -> new WeakHashMap<>());
            var shared = countsInRegistry.get(gauge);
            if (shared != null && shared.gauge().get() == gauge) {
                return shared.count();
            }
            // Removed first: put() on an equal key keeps the OLD key object, whose collection would then drop
            // this entry while the new gauge is still registered.
            countsInRegistry.remove(gauge);
            countsInRegistry.put(gauge, new SharedStallCount(new WeakReference<>(gauge), candidate));
            return candidate;
        }
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

    @Override
    public void takeoverStalled(MessageId.Lane lane, int shard, boolean stalled) {
        var stall = new Stall(lane, shard);
        if (stalled) {
            // Shared count first, own second: the end below takes one off the shared count only after taking it off
            // this observer's own, so the shared count can never be read below zero, even mid-update.
            shardsAwaitingTakeover.incrementAndGet();
            stallsSeenStarting.merge(stall, 1, Integer::sum);
        } else if (endSeenStall(stall)) {
            // Only a stall this observer saw start: one registered while a shard was already stalled is told only that
            // the stall ended. Taking that off the shared count would cancel a stall of another shard, or another
            // observer's, or read -1 and hide the next real stall behind a reading of zero.
            shardsAwaitingTakeover.decrementAndGet();
        }
    }

    /**
     * Take one off this observer's count for {@code stall}, and say whether there was one to take.
     */
    private boolean endSeenStall(Stall stall) {
        var ended = new boolean[1];
        stallsSeenStarting.computeIfPresent(stall, (key, count) -> {
            ended[0] = true;
            return count > 1 ? count - 1 : null;
        });
        return ended[0];
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
        // Same snapshot, so no extra query: the dead-letter breakdown comes back with the depth.
        Gauge.builder(BLOCKED_KEYS_GAUGE, () -> snapshot.get().blockedKeys())
             .description("ordered keys stopped behind a dead letter")
             .tags(withCommonTags())
             .register(registry);
        Gauge.builder(PARKED_GAUGE, () -> snapshot.get().parkedBehindDeadLetter())
             .description("dead letters parked unhandled behind another on their key")
             .tags(withCommonTags())
             .register(registry);
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
