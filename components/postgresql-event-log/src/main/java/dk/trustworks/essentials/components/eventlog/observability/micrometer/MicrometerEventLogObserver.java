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
package dk.trustworks.essentials.components.eventlog.observability.micrometer;

import dk.trustworks.essentials.components.eventlog.EventLogObserver;
import io.micrometer.core.instrument.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The Micrometer binding of {@link EventLogObserver}: a lag gauge, failure, park and lease-loss counters, a page timer
 * and the parked heads (a gauge by state, a retry counter by origin and outcome, the oldest head's age) per subscription. Meter names mirror the old engine's {@code essentials.eventstore.subscription.*} family under the
 * {@code essentials.eventlog.} prefix, with the same tags: {@value #SUBSCRIBER_TAG}, {@value #AGGREGATE_TYPE_TAG} and the
 * optional {@value #MODULE_TAG_NAME}. A per-process {@code instance} tag is left to the registry's common tags (or the
 * scrape), as for every other Essentials meter.
 * <p>
 * Cost: every callback is one map lookup of meters cached per subscription and an increment, a {@link AtomicLong} store or
 * a timer record - per page, per failure or per read, never per event. No registry lookup happens after a subscription's
 * first callback.
 * <p>
 * To combine it with an observer of your own, extend it and call {@code super} in every method you override.
 */
public class MicrometerEventLogObserver implements EventLogObserver {
    public static final String SUBSCRIBER_TAG     = "subscriber_id";
    public static final String AGGREGATE_TYPE_TAG = "aggregate_type";
    public static final String MODULE_TAG_NAME    = "Module";
    public static final String OUTCOME_TAG        = "outcome";
    /** Tag of {@value #PARKED_HEADS_METRIC}: {@code retrying} or {@code terminal} */
    public static final String STATE_TAG          = "state";
    /** Tag of {@value #PARKED_RETRIES_METRIC}: {@code auto} or {@code operator} */
    public static final String ORIGIN_TAG         = "origin";

    /**
     * Gauge: events between the subscription's last saved resume point and the reader's cursor for its aggregate type,
     * {@code max(0, cursor - resumeFrom + 1)}; the new engine's {@code DurableSubscriptions_EventOrder_Diff}. Updated when
     * progress is saved and on every tail read, so it reads the saved position, not the handled one.
     */
    public static final String LAG_METRIC          = "essentials.eventlog.subscription.lag";
    /** Counter of failures reported by {@link #onError}: handler and engine failures, retried or parked after */
    public static final String FAILED_METRIC       = "essentials.eventlog.subscription.failed";
    /** Counter of events parked, or held behind their aggregate's parked head */
    public static final String PARKED_METRIC       = "essentials.eventlog.subscription.parked";
    /** Counter of leases an exclusive subscription lost */
    public static final String LEASE_LOST_METRIC   = "essentials.eventlog.subscription.lease_lost";
    /** Timer of in-transaction pages, from opening the page transaction to its commit or rollback; tag {@value #OUTCOME_TAG} */
    public static final String PAGE_METRIC         = "essentials.eventlog.subscription.page";
    /** Counter of lease keeper passes that failed; not per subscription, as one pass renews every lease of the event log */
    public static final String RENEWAL_FAILED_METRIC = "essentials.eventlog.lease.renewal_failed";
    /**
     * Time gauge per aggregate type (tag {@value #AGGREGATE_TYPE_TAG} only): how long the tail reader's oldest outstanding
     * candidate has waited, zero when none waits. While it grows, the type's watermark stands still and every
     * {@code CONTIGUOUS} subscriber of the type waits with it: a transaction that drew a hole has not ended
     * (docs/design/event-log.md §4). Updated on every tail read.
     */
    public static final String OLDEST_CANDIDATE_AGE_METRIC = "essentials.eventlog.reader.oldest_candidate_age";
    /**
     * Gauge: the subscription's parked heads, tag {@value #STATE_TAG} {@code retrying} (the engine retries them on its
     * backoff) or {@code terminal} (failed with a {@code ParkEventException}; only an operator retries or discards them).
     * Updated when the held set or the retry schedule changes
     */
    public static final String PARKED_HEADS_METRIC = "essentials.eventlog.subscription.parked.heads";
    /**
     * Counter of serves of a parked head, tags {@value #ORIGIN_TAG} ({@code auto} or {@code operator}) and
     * {@value #OUTCOME_TAG} ({@code released}, {@code failed} or {@code deferred})
     */
    public static final String PARKED_RETRIES_METRIC = "essentials.eventlog.subscription.parked.retries";
    /** Time gauge: how long the subscription's oldest parked head has been parked, zero when none is */
    public static final String PARKED_OLDEST_AGE_METRIC = "essentials.eventlog.subscription.parked.oldest.age";

    private final MeterRegistry                                registry;
    private final String                                       moduleTag;
    private final ConcurrentHashMap<Key, SubscriptionMeters>   subscriptions = new ConcurrentHashMap<>();
    /** How many subscriptions with each key run here, from {@link #onStarted} to {@link #onStopped} */
    private final ConcurrentHashMap<Key, Integer> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>        cursors       = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>        candidateAges = new ConcurrentHashMap<>();
    private final Counter                                      renewalFailed;

    public MicrometerEventLogObserver(MeterRegistry registry) {
        this(registry, null);
    }

    /**
     * @param registry  the registry the meters are registered with
     * @param moduleTag the optional {@value #MODULE_TAG_NAME} tag value; {@code null} or blank omits the tag
     */
    public MicrometerEventLogObserver(MeterRegistry registry, String moduleTag) {
        this.registry = requireNonNull(registry, "No registry provided");
        this.moduleTag = moduleTag == null || moduleTag.isBlank() ? null : moduleTag;
        var builder = Counter.builder(RENEWAL_FAILED_METRIC).description("Lease keeper passes that failed");
        if (this.moduleTag != null) {
            builder.tag(MODULE_TAG_NAME, this.moduleTag);
        }
        this.renewalFailed = builder.register(registry);
    }

    private record Key(String subscriber, String aggregateType) {
    }

    private AtomicLong cursor(String aggregateType) {
        return cursors.computeIfAbsent(aggregateType, type -> new AtomicLong(Long.MIN_VALUE));
    }

    /**
     * The meters of a subscription still running here, or {@code null} once the last one with its key stopped: a lease
     * callback racing the stop must not register meters that nothing removes again
     */
    private SubscriptionMeters metersIfRunning(String subscriber, String aggregateType) {
        var key    = new Key(subscriber, aggregateType);
        var meters = subscriptions.get(key);
        return meters != null || !running.containsKey(key) ? meters : meters(subscriber, aggregateType);
    }

    private SubscriptionMeters meters(String subscriber, String aggregateType) {
        var key    = new Key(subscriber, aggregateType);
        var meters = subscriptions.get(key);
        return meters != null ? meters : subscriptions.computeIfAbsent(key, k -> new SubscriptionMeters(k, cursor(k.aggregateType())));
    }

    /** The meters of one subscription, built once */
    private final class SubscriptionMeters {
        final AtomicLong resumeFrom = new AtomicLong(Long.MIN_VALUE);
        final AtomicLong cursor;
        final Counter    failed;
        final Counter    parked;
        final Counter    leaseLost;
        final Timer      committed;
        final Timer      pageFailed;
        final Gauge      lag;
        final AtomicLong retrying          = new AtomicLong();
        final AtomicLong terminal          = new AtomicLong();
        /** When the oldest parked head was parked ({@link System#nanoTime()}), {@link Long#MIN_VALUE} when none is */
        final AtomicLong oldestParkedNanos = new AtomicLong(Long.MIN_VALUE);
        final Gauge      retryingHeads;
        final Gauge      terminalHeads;
        final TimeGauge  oldestParkedAge;
        /** The retry counters, registered on first use: origin and outcome */
        final ConcurrentHashMap<String, Counter> retries = new ConcurrentHashMap<>();
        final Tags       tags;

        SubscriptionMeters(Key key, AtomicLong cursor) {
            this.cursor = cursor;
            var tags = Tags.of(SUBSCRIBER_TAG, key.subscriber(), AGGREGATE_TYPE_TAG, key.aggregateType());
            if (moduleTag != null) {
                tags = tags.and(MODULE_TAG_NAME, moduleTag);
            }
            this.tags = tags;
            retryingHeads = Gauge.builder(PARKED_HEADS_METRIC, retrying, AtomicLong::doubleValue).description("Parked heads").tags(tags).tag(STATE_TAG, "retrying")
                                 .strongReference(true).register(registry);
            terminalHeads = Gauge.builder(PARKED_HEADS_METRIC, terminal, AtomicLong::doubleValue).description("Parked heads").tags(tags).tag(STATE_TAG, "terminal")
                                 .strongReference(true).register(registry);
            oldestParkedAge = TimeGauge.builder(PARKED_OLDEST_AGE_METRIC, oldestParkedNanos, TimeUnit.NANOSECONDS, SubscriptionMeters::age)
                                       .description("How long the oldest parked head has been parked")
                                       .tags(tags)
                                       .strongReference(true)
                                       .register(registry);
            lag = Gauge.builder(LAG_METRIC, this, SubscriptionMeters::lag)
                 .description("Events between the subscription's saved resume point and the reader's cursor")
                 .tags(tags)
                 .strongReference(true)
                 .register(registry);
            failed = Counter.builder(FAILED_METRIC).description("Subscription failures (retried or parked after)").tags(tags).register(registry);
            parked = Counter.builder(PARKED_METRIC).description("Events parked or held behind a parked head").tags(tags).register(registry);
            leaseLost = Counter.builder(LEASE_LOST_METRIC).description("Leases the exclusive subscription lost").tags(tags).register(registry);
            committed = Timer.builder(PAGE_METRIC).description("In-transaction page time").tags(tags).tag(OUTCOME_TAG, "committed").register(registry);
            pageFailed = Timer.builder(PAGE_METRIC).description("In-transaction page time").tags(tags).tag(OUTCOME_TAG, "failed").register(registry);
        }

        void remove() {
            for (var meter : List.of(lag, failed, parked, leaseLost, committed, pageFailed, retryingHeads, terminalHeads, oldestParkedAge)) {
                registry.remove(meter);
            }
            for (var meter : retries.values()) {
                registry.remove(meter);
            }
        }

        static double age(AtomicLong parkedAtNanos) {
            var at = parkedAtNanos.get();
            return at == Long.MIN_VALUE ? 0 : Math.max(0, System.nanoTime() - at);
        }

        Counter retries(boolean auto, ParkedRetryOutcome outcome) {
            var origin = auto ? "auto" : "operator";
            var value  = outcome.name().toLowerCase(java.util.Locale.ROOT);
            return retries.computeIfAbsent(origin + ':' + value, k -> Counter.builder(PARKED_RETRIES_METRIC)
                                                                             .description("Serves of a parked head by origin and outcome")
                                                                             .tags(tags)
                                                                             .tag(ORIGIN_TAG, origin)
                                                                             .tag(OUTCOME_TAG, value)
                                                                             .register(registry));
        }

        double lag() {
            var from = resumeFrom.get();
            var tail = cursor.get();
            if (from == Long.MIN_VALUE || tail == Long.MIN_VALUE) {
                return 0;
            }
            return Math.max(0, tail - from + 1);
        }
    }

    @Override
    public void onRead(String aggregateType, boolean combined, long nanos, int rows, int outstandingHoles, int outstandingCandidates, java.time.Duration oldestCandidateAge) {
        candidateAges.computeIfAbsent(aggregateType, type -> {
            var age  = new AtomicLong();
            var tags = Tags.of(AGGREGATE_TYPE_TAG, type);
            if (moduleTag != null) {
                tags = tags.and(MODULE_TAG_NAME, moduleTag);
            }
            TimeGauge.builder(OLDEST_CANDIDATE_AGE_METRIC, age, TimeUnit.NANOSECONDS, AtomicLong::doubleValue)
                     .description("How long the tail reader's oldest outstanding candidate has waited")
                     .tags(tags)
                     .register(registry);
            return age;
        }).set(oldestCandidateAge.toNanos());
    }

    @Override
    public void onReaderCursor(String aggregateType, long cursor) {
        cursor(aggregateType).set(cursor);
    }

    @Override
    public void onProgressSaved(String subscriber, String aggregateType, long resumeFrom) {
        meters(subscriber, aggregateType).resumeFrom.set(resumeFrom);
    }

    @Override
    public void onError(String subscriber, String aggregateType, Throwable error) {
        meters(subscriber, aggregateType).failed.increment();
    }

    @Override
    public void onParked(String subscriber, String aggregateType, long globalOrder, Long blockedBy, int attempts) {
        meters(subscriber, aggregateType).parked.increment();
    }

    @Override
    public void onParkedRetry(String subscriber, String aggregateType, long head, boolean auto, ParkedRetryOutcome outcome, int retries, java.time.Duration nextIn) {
        meters(subscriber, aggregateType).retries(auto, outcome).increment();
    }

    @Override
    public void onParkedHeads(String subscriber, String aggregateType, int retrying, int terminal, java.time.Duration oldestAge) {
        var meters = metersIfRunning(subscriber, aggregateType);
        if (meters == null) {
            return;
        }
        meters.retrying.set(retrying);
        meters.terminal.set(terminal);
        meters.oldestParkedNanos.set(retrying + terminal == 0 ? Long.MIN_VALUE : System.nanoTime() - oldestAge.toNanos());
    }

    @Override
    public void onLeaseLost(String subscriber, String aggregateType, String reason) {
        var meters = metersIfRunning(subscriber, aggregateType);
        if (meters == null) {
            return;
        }
        meters.leaseLost.increment();
        // A standby delivers nothing: its saved position freezes while the shared cursor moves on, so the lag would grow
        // without bound on every node but the owner. Unknown (0) until it owns the lease and saves again
        meters.resumeFrom.set(Long.MIN_VALUE);
    }

    @Override
    public void onLeaseSuspended(String subscriber, String aggregateType) {
        var meters = metersIfRunning(subscriber, aggregateType);
        if (meters != null) {
            meters.resumeFrom.set(Long.MIN_VALUE);
        }
    }

    @Override
    public void onStarted(String subscriber, String aggregateType) {
        running.merge(new Key(subscriber, aggregateType), 1, Integer::sum);
    }

    @Override
    public void onStopped(String subscriber, String aggregateType) {
        // Instances of one subscriber on one type share their meters (same tags): only the last one to stop removes them
        running.compute(new Key(subscriber, aggregateType), (key, count) -> {
            if (count != null && count > 1) {
                return count - 1;
            }
            var meters = subscriptions.remove(key);
            if (meters != null) {
                meters.remove();
            }
            return null;
        });
    }

    @Override
    public void onRenewalFailed(Throwable cause) {
        renewalFailed.increment();
    }

    @Override
    public void onPageCommitted(String subscriber, String aggregateType, int size, long nanos) {
        meters(subscriber, aggregateType).committed.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void onPageFailed(String subscriber, String aggregateType, int size, long nanos, Throwable cause) {
        meters(subscriber, aggregateType).pageFailed.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + (moduleTag != null ? "[" + moduleTag + "]" : "");
    }
}
