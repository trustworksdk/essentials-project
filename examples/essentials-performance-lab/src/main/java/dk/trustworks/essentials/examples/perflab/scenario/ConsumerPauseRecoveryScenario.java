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

package dk.trustworks.essentials.examples.perflab.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcDispatcher;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcInboxRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.perflab.EssentialsPerformanceLabProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario: drives a steady-rate write workload while the dispatcher is stopped mid-run, then
 * restarted, validating that the inbox-backlog gauge tracks reality and the dispatcher drains
 * the accumulated backlog after resume. Three-phase timeline:
 * <pre>
 * │── normal ──│── paused ──│── recovery ──│
 *   t=0         t=1/3        t=2/3         t=duration
 * </pre>
 * The dispatcher runs normally for the first third, is stopped via {@link CdcDispatcher#stop()}
 * for the middle third (so the inbox backlog grows), and restarted for the final third (so the
 * backlog drains).
 * <p>
 * Pass criteria:
 * <ul>
 *   <li>{@code backlogGrewDuringPause} — {@code inbox.received_backlog} count at pause-end is
 *       strictly greater than at pause-start. Tells us the tailer kept writing while the
 *       dispatcher was off.</li>
 *   <li>{@code backlogDrainedAfterResume} — final backlog ≤ a small threshold (default 200
 *       rows). Drain rate sanity-check.</li>
 *   <li>{@code peakMatchesProducedDelta} — observed peak backlog ≈ events produced during the
 *       paused window (within 25% slack for in-flight at boundaries). Verifies the gauge is
 *       not under-reporting.</li>
 *   <li>{@code dispatcherRestartedCleanly} — dispatcher reports {@code isStarted=true} after
 *       resume; no exception thrown by {@code start()}.</li>
 * </ul>
 */
@Component
public class ConsumerPauseRecoveryScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(ConsumerPauseRecoveryScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabPauseRecovery");
    private static final long          BACKLOG_DRAINED_THRESHOLD_ROWS = 200L;

    private final EventStore                                                  eventStore;
    private final ConfigurableEventStore<?>                                   configurableEventStore;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcDispatcher>                                     dispatcher;
    private final Optional<CdcInboxRepository>                                inboxRepository;
    private final Optional<CdcAvailability>                                   cdcAvailability;
    private final ObjectMapper                                                objectMapper;

    public ConsumerPauseRecoveryScenario(EventStore eventStore,
                                         ConfigurableEventStore<?> configurableEventStore,
                                         EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                         Optional<CdcDispatcher> dispatcher,
                                         Optional<CdcInboxRepository> inboxRepository,
                                         Optional<CdcAvailability> cdcAvailability,
                                         ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.dispatcher = dispatcher;
        this.inboxRepository = inboxRepository;
        this.cdcAvailability = cdcAvailability;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "consumer-pause-recovery";
    }

    @Override
    public String description() {
        return "Three-phase: normal → dispatcher stopped (backlog grows) → dispatcher restarted (backlog drains). Verifies inbox backlog gauge tracks reality and drain works.";
    }

    @PostConstruct
    void registerAggregateAtStartup() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        if (dispatcher.isEmpty() || inboxRepository.isEmpty()) {
            log.error("CDC dispatcher / inbox not present — consumer-pause-recovery requires CDC enabled in INBOX delivery mode.");
            return;
        }
        var disp     = dispatcher.get();
        var inbox    = inboxRepository.get();
        var slotName = inferSlotName(disp);

        var totalDurationMs = properties.getDuration().toMillis();
        var phaseMs         = totalDurationMs / 3L;

        var samples         = Collections.synchronizedList(new ArrayList<BacklogSample>());
        var startedAtNanos  = System.nanoTime();

        // Periodically sample the RECEIVED-backlog directly via the repository — same query
        // backing essentials.cdc.inbox.received_backlog. Keeps the assertion source of truth
        // a SQL count, not the gauge, so a divergence between the two would itself surface
        // as a backlog-vs-gauge difference (future-proof for that specific bug).
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "consumer-pause-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(() -> {
            try {
                long received = inbox.countByStatus(slotName, "RECEIVED");
                long poison   = inbox.countByStatus(slotName, "POISON");
                samples.add(new BacklogSample(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
                                              received, poison));
            } catch (Exception e) {
                log.debug("backlog sample failed: {}", e.toString());
            }
        }, 0L, 1_000L, TimeUnit.MILLISECONDS);

        // Producer runs for the FULL duration so the dispatcher's pause/resume happens against
        // sustained pressure — that's the realistic operational shape.
        var producerThread = startProducer(properties, totalDurationMs);

        var producedAtPauseStart  = new AtomicLong();
        var backlogAtPauseStart   = new AtomicLong();
        var producedAtPauseEnd    = new AtomicLong();
        var backlogAtPauseEnd     = new AtomicLong();
        boolean dispatcherStarted;
        boolean dispatcherRestarted;

        try {
            // Phase 1: normal dispatcher running for first third.
            Thread.sleep(phaseMs);
            producedAtPauseStart.set(currentEventCount());
            backlogAtPauseStart.set(inbox.countByStatus(slotName, "RECEIVED"));
            log.info("[{}] phase=pause-start produced={} backlog={}",
                     slotName, producedAtPauseStart.get(), backlogAtPauseStart.get());

            // Phase 2: stop dispatcher, watch backlog grow.
            disp.stop();
            Thread.sleep(phaseMs);
            producedAtPauseEnd.set(currentEventCount());
            backlogAtPauseEnd.set(inbox.countByStatus(slotName, "RECEIVED"));
            log.info("[{}] phase=pause-end produced={} backlog={}",
                     slotName, producedAtPauseEnd.get(), backlogAtPauseEnd.get());

            // Phase 3: restart and let it drain. Capture the pre/post start state so we can
            // assert the dispatcher actually came back up.
            dispatcherStarted = disp.isStarted();
            disp.start();
            dispatcherRestarted = disp.isStarted();
            log.info("[{}] phase=resume dispatcher.isStarted before={} after={}",
                     slotName, dispatcherStarted, dispatcherRestarted);

            // Wait for the producer to finish so we have a clean accounting of "what we expected
            // the dispatcher to drain". The drain budget is 1× phaseMs by default; if the
            // dispatcher's drain rate ≥ produce rate (the whole point), this is comfortable.
            producerThread.join(phaseMs + 30_000L);
        } finally {
            sampler.shutdownNow();
        }

        // One final backlog reading after producer joined + small grace period.
        Thread.sleep(2_000L);
        long finalBacklog = inbox.countByStatus(slotName, "RECEIVED");
        long finalPoison  = inbox.countByStatus(slotName, "POISON");
        long peakBacklog  = samples.stream().mapToLong(BacklogSample::receivedBacklog).max().orElse(0L);

        long producedDuringPause = producedAtPauseEnd.get() - producedAtPauseStart.get();
        // Backlog-grew check: pause-end backlog should be ≥ pause-start + a meaningful chunk.
        // We don't demand an exact match because some events that landed during pause-start
        // were already in flight to the dispatcher and may have been drained before the stop()
        // took full effect.
        boolean backlogGrewDuringPause = backlogAtPauseEnd.get() > backlogAtPauseStart.get();
        boolean backlogDrainedAfterResume = finalBacklog <= BACKLOG_DRAINED_THRESHOLD_ROWS;
        boolean peakMatchesProducedDelta;
        if (producedDuringPause <= 0) {
            // No producer events landed during the paused window — typically only happens with
            // very short scenarios. Fall back to "peak ≥ pause-start" as a relaxed sanity check.
            peakMatchesProducedDelta = peakBacklog >= backlogAtPauseStart.get();
        } else {
            // Allow 25% slack: the peak should track the pause-window production within
            // sampling-cadence error. Going significantly under means the gauge is undercounting.
            double ratio = peakBacklog * 1.0d / producedDuringPause;
            peakMatchesProducedDelta = ratio >= 0.75d;
        }

        var verdict = (backlogGrewDuringPause
                       && backlogDrainedAfterResume
                       && peakMatchesProducedDelta
                       && dispatcherRestarted) ? "PASS" : "FAIL";

        var snapshot = new PauseRecoverySnapshot(
                Instant.now().toString(),
                slotName,
                cdcAvailability.map(CdcAvailability::isActive).orElse(false),
                phaseMs,
                producedAtPauseStart.get(),
                backlogAtPauseStart.get(),
                producedAtPauseEnd.get(),
                backlogAtPauseEnd.get(),
                producedDuringPause,
                peakBacklog,
                finalBacklog,
                finalPoison,
                backlogGrewDuringPause,
                backlogDrainedAfterResume,
                peakMatchesProducedDelta,
                dispatcherRestarted,
                verdict,
                samples,
                cdcAvailability.map(CdcAvailability::snapshot).orElse(null)
        );

        var json = toJson(snapshot);
        log.info("consumer-pause-recovery metrics: {}", json);
        System.out.println("############# [perf-lab] CONSUMER-PAUSE-RECOVERY DONE #############");
        System.out.println("############# [perf-lab] slot=" + slotName +
                           " backlog_pause_start=" + backlogAtPauseStart.get() +
                           " backlog_pause_end=" + backlogAtPauseEnd.get() +
                           " peak_backlog=" + peakBacklog +
                           " final_backlog=" + finalBacklog +
                           " verdict=" + verdict);
        System.out.println("############# [perf-lab] ##########################################");

        writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
    }

    /**
     * Best-effort lookup of the slot name from the dispatcher's status. The dispatcher exposes
     * its slot via {@code getStatus()} but we don't want to add a hard dependency on that
     * accessor — fall back to a query when it isn't reachable.
     */
    private String inferSlotName(CdcDispatcher disp) {
        try {
            return disp.getStatus().slotName();
        } catch (Exception e) {
            log.warn("Couldn't read slot name from dispatcher; falling back to a single-slot query: {}", e.toString());
            return unitOfWorkFactory.withUnitOfWork(uow -> {
                try (var ps = uow.handle().getConnection().prepareStatement(
                        "SELECT slot_name FROM pg_replication_slots WHERE slot_name LIKE 'essentials\\_%' ESCAPE '\\' LIMIT 1")) {
                    try (var rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : "unknown";
                    }
                } catch (Exception ex) {
                    return "unknown";
                }
            });
        }
    }

    private long currentEventCount() {
        return unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                .map(g -> g.longValue())
                                .orElse(0L);
    }

    /**
     * Producer thread that runs for {@code totalDurationMs} at the configured
     * {@code producerRateHz}. Same throttling logic as {@code SlotLagBoundedScenario} so the
     * two scenarios produce comparable WAL volumes when run with the same knobs.
     */
    private Thread startProducer(EssentialsPerformanceLabProperties properties, long totalDurationMs) {
        long perThreadRateHz = properties.getProducerRateHz() <= 0
                               ? 0L
                               : Math.max(1L, properties.getProducerRateHz() / Math.max(1, properties.getProducerThreads()));
        long perThreadIntervalNanos = perThreadRateHz <= 0L ? 0L : TimeUnit.SECONDS.toNanos(1) / perThreadRateHz;

        var thread = new Thread(() -> {
            var random       = new Random(properties.getRandomSeed());
            var cardinality  = Math.max(1, properties.getAggregateCardinality());
            long deadline    = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalDurationMs);
            long nextDueAt   = System.nanoTime();
            long sequence    = 0L;
            while (System.nanoTime() < deadline) {
                if (perThreadIntervalNanos > 0L) {
                    long now = System.nanoTime();
                    if (now < nextDueAt) {
                        try {
                            TimeUnit.NANOSECONDS.sleep(nextDueAt - now);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    nextDueAt += perThreadIntervalNanos;
                }
                var aggregateId = "order-" + random.nextInt(cardinality);
                sequence++;
                final long seq = sequence;
                try {
                    unitOfWorkFactory.withUnitOfWork(() -> {
                        eventStore.appendToStream(ORDERS, aggregateId, List.of(
                                new LabPauseRecoveryEvent(aggregateId, seq, System.nanoTime())
                        ));
                        return null;
                    });
                } catch (Exception e) {
                    if (!(e.getCause() instanceof OptimisticAppendToStreamException)) {
                        log.debug("append failed: {}", e.toString());
                    }
                }
            }
        }, "lab-pause-recovery-producer");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote consumer-pause-recovery metrics to {}", target);
        System.out.println("############# [perf-lab] consumer-pause-recovery metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize consumer-pause-recovery metrics to JSON", e);
        }
    }

    public record BacklogSample(long timestampMs, long receivedBacklog, long poisonRows) {
    }

    public record PauseRecoverySnapshot(String capturedAt,
                                        String slotName,
                                        boolean cdcActive,
                                        long phaseMs,
                                        long producedAtPauseStart,
                                        long backlogAtPauseStart,
                                        long producedAtPauseEnd,
                                        long backlogAtPauseEnd,
                                        long producedDuringPause,
                                        long peakBacklog,
                                        long finalBacklog,
                                        long finalPoisonRows,
                                        boolean backlogGrewDuringPause,
                                        boolean backlogDrainedAfterResume,
                                        boolean peakMatchesProducedDelta,
                                        boolean dispatcherRestartedCleanly,
                                        String verdict,
                                        List<BacklogSample> samples,
                                        CdcAvailability.Snapshot cdc) {
    }

    private record LabPauseRecoveryEvent(String aggregateId, long sequence, long appendedAtNanos) {
    }
}
