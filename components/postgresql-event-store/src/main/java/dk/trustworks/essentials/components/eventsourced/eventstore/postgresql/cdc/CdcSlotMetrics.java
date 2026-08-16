/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.CdcSlotProperties;
import dk.trustworks.essentials.components.foundation.Lifecycle;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Background sampler that publishes the most operationally important
 * {@code pg_replication_slots} fields as Micrometer gauges. Sourced from
 * {@link WalReplicationTailer#getSlotStateSnapshot()} on a fixed cadence so the slot's
 * disk-overflow risk profile (see {@code cdc.md} §5) is visible to dashboards and
 * alerting without anyone having to run SQL.
 * <p>
 * Published gauges (all tagged with {@code slot=<slotName>}):
 * <ul>
 *   <li>{@code essentials.cdc.slot.lag_bytes} — bytes of WAL retained past
 *       {@code confirmed_flush_lsn}. Steady growth = slot stuck = disk overflow risk.</li>
 *   <li>{@code essentials.cdc.slot.active} — {@code 1} if a streaming consumer is currently
 *       attached, {@code 0} otherwise.</li>
 *   <li>{@code essentials.cdc.slot.wal_status} — numeric encoding of
 *       {@link WalReplicationTailer.SlotState.WalStatus}: {@code 0=UNKNOWN},
 *       {@code 1=RESERVED} (healthy), {@code 2=EXTENDED}, {@code 3=UNRESERVED},
 *       {@code 4=LOST}. Alert {@code > 1} = warn, {@code > 2} = page.</li>
 *   <li>{@code essentials.cdc.slot.inactive_since_seconds} — number of seconds the slot
 *       has been inactive. {@code 0} when the slot is currently active. A growing value
 *       on an inactive slot is the orphaned-slot signal.</li>
 * </ul>
 * <p>
 * Idempotent {@link #start()} / {@link #stop()}. If sampling fails (e.g. transient DB
 * outage), the underlying snapshot returns {@link Optional#empty()}, the gauges
 * retain their last good values, and the next tick retries — the scheduler is never
 * suppressed.
 */
public final class CdcSlotMetrics implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(CdcSlotMetrics.class);

    private final WalReplicationTailer tailer;
    private final MeterRegistry        meterRegistry;
    private final String               slotName;
    private final CdcSlotProperties    slotProperties;

    private final AtomicBoolean            started = new AtomicBoolean(false);
    private       ScheduledExecutorService executor;
    private       Future<?>                scheduled;

    // Gauge-backing holders. Each gauge captures one of these; refresh() updates them in place.
    // Initial values are deliberately benign so a not-yet-sampled gauge doesn't false-alert:
    //  - lagBytes / inactiveSinceSeconds = 0
    //  - active = 0 (treated as "no slot known yet" not "slot down" — combine with wal_status
    //    in alerts)
    //  - walStatus = 0 (UNKNOWN)
    private final AtomicLong    lagBytes             = new AtomicLong(0);
    private final AtomicInteger active               = new AtomicInteger(0);
    private final AtomicInteger walStatusCode        = new AtomicInteger(WalReplicationTailer.SlotState.WalStatus.UNKNOWN.code());
    private final AtomicLong    inactiveSinceSeconds = new AtomicLong(0);

    /**
     * @param tailer         the tailer whose slot is being measured
     * @param meterRegistry  the registry to register the slot gauges on, or {@code null} for no metrics.
     *                       Nullable rather than {@code Optional}: this class registers Micrometer {@code Gauge}s and
     *                       {@code Counter}s directly, which a {@code MeasurementTaker} — a timing facade — cannot express,
     *                       so the registry itself stays the currency here
     * @param slotName       the replication slot name
     * @param slotProperties the slot configuration, including whether metrics are enabled at all
     */
    public CdcSlotMetrics(WalReplicationTailer tailer,
                          MeterRegistry meterRegistry,
                          String slotName,
                          CdcSlotProperties slotProperties) {
        this.tailer = requireNonNull(tailer, "tailer cannot be null");
        this.meterRegistry = meterRegistry;
        this.slotName = requireNonNull(slotName, "slotName cannot be null");
        this.slotProperties = requireNonNull(slotProperties, "slotProperties cannot be null");
    }

    /**
     * @param tailer         the tailer whose slot is being measured
     * @param meterRegistry  an Optional registry to register the slot gauges on
     * @param slotName       the replication slot name
     * @param slotProperties the slot configuration
     * @deprecated Use {@link #CdcSlotMetrics(WalReplicationTailer, MeterRegistry, String, CdcSlotProperties)}, passing
     *         {@code null} for "no metrics". The {@code Optional} was unwrapped to a nullable field on the first line
     *         of the body, so it never bought anything. This constructor delegates and behaves identically.
     */
    @Deprecated(forRemoval = true, since = "0.40.x")
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcSlotMetrics(WalReplicationTailer tailer,
                          Optional<MeterRegistry> meterRegistry,
                          String slotName,
                          CdcSlotProperties slotProperties) {
        this(tailer,
             requireNonNull(meterRegistry, "meterRegistry cannot be null").orElse(null),
             slotName,
             slotProperties);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;

        if (!slotProperties.isMetricsEnabled()) {
            started.set(false);
            log.info("[{}] CDC slot metrics disabled via essentials.eventstore.cdc.slot.metrics-enabled=false", slotName);
            return;
        }

        Duration interval = slotProperties.getMetricsInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            started.set(false);
            log.warn("[{}] CDC slot metrics not started — metricsInterval must be > 0 (got {})", slotName, interval);
            return;
        }

        registerGauges();

        long intervalMs = Math.max(1_000L, interval.toMillis());
        log.info("[{}] ⚙️ Starting CDC slot metrics (interval={} ms)", slotName, intervalMs);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cdc-slot-metrics-" + slotName);
            t.setDaemon(true);
            return t;
        });
        // Initial tick at t=0 so dashboards have real values immediately rather than the
        // benign defaults; subsequent ticks at the configured interval.
        scheduled = executor.scheduleWithFixedDelay(this::refreshSafely, 0L, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) return;
        try {
            if (scheduled != null) scheduled.cancel(true);
        } finally {
            if (executor != null) executor.shutdownNow();
        }
        log.info("[{}] 🛑 CDC slot metrics stopped", slotName);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    private void registerGauges() {
        if (meterRegistry == null) return;
        Gauge.builder("essentials.cdc.slot.lag_bytes", lagBytes, AtomicLong::get)
             .description("Bytes of WAL retained past the slot's confirmed_flush_lsn. Steady growth = slot stuck = disk-overflow risk.")
             .baseUnit("bytes")
             .tag("slot", slotName)
             .register(meterRegistry);

        Gauge.builder("essentials.cdc.slot.active", active, AtomicInteger::get)
             .description("1 if a streaming consumer is currently attached to the slot, 0 otherwise.")
             .tag("slot", slotName)
             .register(meterRegistry);

        Gauge.builder("essentials.cdc.slot.wal_status", walStatusCode, AtomicInteger::get)
             .description("Numeric pg_replication_slots.wal_status: 0=UNKNOWN, 1=RESERVED, 2=EXTENDED, 3=UNRESERVED, 4=LOST. Alert > 1 = warn, > 2 = page.")
             .tag("slot", slotName)
             .register(meterRegistry);

        Gauge.builder("essentials.cdc.slot.inactive_since_seconds", inactiveSinceSeconds, AtomicLong::get)
             .description("Seconds since the slot last had a streaming consumer. 0 when active. Growing value on an inactive slot = orphaned-slot signal.")
             .baseUnit("seconds")
             .tag("slot", slotName)
             .register(meterRegistry);
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (Throwable t) {
            // Never let the scheduler suppress future ticks. Sampling failures must not silence
            // the metric — the previous value stays, the next tick retries.
            log.debug("[{}] CDC slot metrics refresh failed — will retry next interval", slotName, t);
        }
    }

    private void refresh() {
        var snapshot = tailer.getSlotStateSnapshot();
        if (snapshot.isEmpty()) {
            // Slot not found / query failed. Don't clobber the last-known good values — leave
            // them in place so a brief DB blip doesn't cause an alerting glitch. The wal_status
            // gauge stays at whatever was last observed.
            return;
        }
        var s = snapshot.get();
        lagBytes.set(s.lagBytes());
        active.set(s.active() ? 1 : 0);
        walStatusCode.set(s.walStatus() != null ? s.walStatus().code()
                                                : WalReplicationTailer.SlotState.WalStatus.UNKNOWN.code());
        // inactive_since_seconds is null in pg_replication_slots when the slot is active. We
        // surface 0 in that case so dashboards always have a numeric value; the {@code active}
        // gauge is the orthogonal signal alerts should combine with.
        Long inactive = s.inactiveSinceSeconds();
        inactiveSinceSeconds.set(inactive == null ? 0L : Math.max(0L, inactive));
    }
}
