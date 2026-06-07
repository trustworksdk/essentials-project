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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStoreSubscription;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcAvailability;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcConsumerGroup;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcInboxRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcSlotNameProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
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
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scenario: drives a steady stream of valid events while injecting {@code poisonCount}
 * deliberately-malformed inbox rows ({@code "not-json"} payloads) using
 * {@link CdcInboxRepository#insertRaw}. Validates that the framework's poison-handling path
 * (P3 metrics + dispatcher quarantine policy) keeps the valid stream flowing at the expected
 * rate while quarantining bad rows.
 * <p>
 * Pass criteria:
 * <ul>
 *   <li>{@code poisonCountTracked} — final {@code essentials.cdc.inbox.poison_rows} count
 *       equals the number of malformed rows we injected. The repository-level count is the
 *       ground truth here; if it diverges from {@code countByStatus("POISON")} that's the bug.</li>
 *   <li>{@code validDeliveryRateOk} — delivered valid events ≥ 99% of produced valid events,
 *       same threshold as {@code SlotLagBoundedScenario}. Tells us poison rows don't block the
 *       drain path.</li>
 *   <li>{@code receivedBacklogDrained} — final {@code RECEIVED} backlog ≤ 200 rows. Poison
 *       rows live in the {@code POISON} bucket; they shouldn't accumulate in {@code RECEIVED}.</li>
 *   <li>{@code dispatcherStillRunning} — dispatcher's {@code Lifecycle#isStarted} is true at
 *       end. Verifies the {@code QUARANTINE_AND_CONTINUE} policy didn't escalate to
 *       {@code STOP}.</li>
 * </ul>
 * Default {@code poisonCount = 100} via {@link EssentialsPerformanceLabProperties#getPoisonFloodCount()}.
 */
@Component
public class PoisonFloodEnduranceScenario implements LabScenario {
    private static final Logger log = LoggerFactory.getLogger(PoisonFloodEnduranceScenario.class);

    private static final AggregateType ORDERS = AggregateType.of("LabPoisonFlood");
    private static final long          BACKLOG_DRAINED_THRESHOLD_ROWS = 200L;

    private final EventStore                                                  eventStore;
    private final ConfigurableEventStore<?>                                   configurableEventStore;
    private final EventStoreSubscriptionManager                               subscriptionManager;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final Optional<CdcInboxRepository>                                inboxRepository;
    private final Optional<CdcSlotNameProvider>                               slotNameProvider;
    private final Optional<CdcConsumerGroup>                                  consumerGroup;
    private final Optional<CdcAvailability>                                   cdcAvailability;
    private final ObjectMapper                                                objectMapper;

    public PoisonFloodEnduranceScenario(EventStore eventStore,
                                        ConfigurableEventStore<?> configurableEventStore,
                                        EventStoreSubscriptionManager subscriptionManager,
                                        EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                        Optional<CdcInboxRepository> inboxRepository,
                                        Optional<CdcSlotNameProvider> slotNameProvider,
                                        Optional<CdcConsumerGroup> consumerGroup,
                                        Optional<CdcAvailability> cdcAvailability,
                                        ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.configurableEventStore = configurableEventStore;
        this.subscriptionManager = subscriptionManager;
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.inboxRepository = inboxRepository;
        this.slotNameProvider = slotNameProvider;
        this.consumerGroup = consumerGroup;
        this.cdcAvailability = cdcAvailability;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "poison-flood";
    }

    @Override
    public String description() {
        return "Injects N malformed inbox rows alongside a steady valid-event workload; verifies poison gauge tracks the count, valid delivery rate stays high, dispatcher keeps running.";
    }

    @PostConstruct
    void registerAggregateAtStartup() {
        if (configurableEventStore.findAggregateEventStreamConfiguration(ORDERS).isEmpty()) {
            configurableEventStore.addAggregateEventStreamConfiguration(ORDERS, String.class);
        }
    }

    @Override
    public void run(EssentialsPerformanceLabProperties properties) throws Exception {
        if (inboxRepository.isEmpty() || slotNameProvider.isEmpty() || consumerGroup.isEmpty()) {
            log.error("CDC inbox / slot config missing — poison-flood requires CDC enabled in INBOX delivery mode.");
            return;
        }
        var inbox    = inboxRepository.get();
        var slotName = slotNameProvider.get().slotName(consumerGroup.get());

        var poisonCount = Math.max(0, properties.getPoisonFloodCount());
        log.info("[{}] poison-flood: poisonCount={} duration={}", slotName, poisonCount, properties.getDuration());

        // Subscribe to drain valid events. We count delivered events to validate that poison
        // doesn't block the dispatcher's normal path.
        var subscriberId = SubscriberId.of("lab-poison-flood-" + UUID.randomUUID());
        var startFrom    = unitOfWorkFactory.withUnitOfWork(() -> eventStore.findHighestGlobalEventOrderPersisted(ORDERS))
                                            .map(GlobalEventOrder::increment)
                                            .orElse(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER);
        var validDelivered = new AtomicLong();
        EventStoreSubscription subscription = subscriptionManager.subscribeToAggregateEventsAsynchronously(
                subscriberId, ORDERS, startFrom, event -> validDelivered.incrementAndGet());

        try {
            // Inject the poison flood up-front so the dispatcher sees them immediately and
            // their handling overlaps with the valid stream that follows. Each poison row gets
            // a deterministic LSN seeded from the run's UUID — keeps the dataset reproducible
            // across reruns and avoids collision with real WAL LSNs (which always start with
            // a non-zero high half).
            var poisonLsns = injectPoisonFlood(inbox, slotName, poisonCount);

            var producedValid = runProducer(properties);

            // Drain phase: same budget shape as SlotLagBoundedScenario for consistency.
            var drainBudgetMs = Math.max(30_000L, properties.getDuration().toMillis() * 3L / 2L);
            var drainDeadline = System.currentTimeMillis() + drainBudgetMs;
            while (validDelivered.get() < producedValid && System.currentTimeMillis() < drainDeadline) {
                Thread.sleep(50L);
            }
            // One last small grace so the dispatcher's last batch flushes through.
            Thread.sleep(2_000L);

            long finalPoisonRows  = inbox.countByStatus(slotName, "POISON");
            long finalReceived    = inbox.countByStatus(slotName, "RECEIVED");
            long finalDispatched  = inbox.countByStatus(slotName, "DISPATCHED");

            // Subscription manager's polling cadence + resume-point persistence introduces a
            // tail of in-flight events at scenario shutdown — same softening as the lag scenario.
            boolean validDeliveryRateOk = producedValid == 0 || validDelivered.get() >= (long) Math.ceil(producedValid * 0.99d);
            boolean poisonCountTracked  = finalPoisonRows == poisonLsns.size();
            boolean receivedDrained     = finalReceived <= BACKLOG_DRAINED_THRESHOLD_ROWS;
            boolean dispatcherRunning   = cdcAvailability.map(CdcAvailability::isActive).orElse(true);

            var verdict = (poisonCountTracked && validDeliveryRateOk && receivedDrained && dispatcherRunning) ? "PASS" : "FAIL";

            var snapshot = new PoisonFloodSnapshot(
                    Instant.now().toString(),
                    slotName,
                    cdcAvailability.map(CdcAvailability::isActive).orElse(false),
                    poisonLsns.size(),
                    finalPoisonRows,
                    finalReceived,
                    finalDispatched,
                    producedValid,
                    validDelivered.get(),
                    poisonCountTracked,
                    validDeliveryRateOk,
                    receivedDrained,
                    dispatcherRunning,
                    verdict,
                    cdcAvailability.map(CdcAvailability::snapshot).orElse(null)
            );

            var json = toJson(snapshot);
            log.info("poison-flood metrics: {}", json);
            System.out.println("############# [perf-lab] POISON-FLOOD DONE #############");
            System.out.println("############# [perf-lab] slot=" + slotName +
                               " injected=" + poisonLsns.size() +
                               " poison_rows=" + finalPoisonRows +
                               " produced_valid=" + producedValid +
                               " delivered_valid=" + validDelivered.get() +
                               " final_received=" + finalReceived +
                               " verdict=" + verdict);
            System.out.println("############# [perf-lab] ##############################");

            writeMetricsIfConfigured(properties.getMetricsOutputFile(), json);
        } finally {
            subscription.unsubscribe();
        }
    }

    /**
     * Inject {@code count} malformed inbox rows. Returns the list of synthetic LSN strings
     * used so the caller can reconcile the count and (optionally) clean up later.
     * <p>
     * Synthetic LSNs use the form {@code "DEAD/<rolling-hex>"} — the high half is well above
     * any real WAL LSN PostgreSQL will issue during the test, so the framework's
     * {@code (slot_name, lsn)} unique constraint never collides with a real-event LSN that
     * happens to be processed concurrently.
     */
    private List<String> injectPoisonFlood(CdcInboxRepository inbox, String slotName, int count) {
        if (count <= 0) return List.of();
        var lsns = new java.util.ArrayList<String>(count);
        var random = new Random();
        // Payload chosen to fail decode under both supported plugins:
        //   - pgoutput: a bare 'I' byte triggers PgOutputMessageDecoder#decodeInsert which calls
        //     buffer.getInt() against the now-empty buffer → BufferUnderflowException.
        //   - wal2json: 'I' alone isn't valid JSON → JsonParseException.
        // Either way the dispatcher's QUARANTINE_AND_CONTINUE policy moves the row to POISON.
        // Plain text payloads like "{not-json}" instead get treated as IgnoredMessage by
        // pgoutput (unknown type byte → empty decode, not exception → not poison) and silently
        // dispatched, which defeats the test.
        var payload = "I";
        for (int i = 0; i < count; i++) {
            // Random low-half so two concurrent runs against the same DB don't trample each
            // other's seed rows; high-half = DEAD keeps us out of the real-LSN range.
            var lsn = "DEAD/" + Long.toHexString(random.nextLong() & 0xFFFFFFFFL).toUpperCase();
            try {
                inbox.insertRaw(slotName, lsn, payload, "RECEIVED");
                lsns.add(lsn);
            } catch (Exception e) {
                log.debug("poison insert collision (skipping): lsn={} err={}", lsn, e.toString());
            }
        }
        log.info("[{}] injected {} poison rows", slotName, lsns.size());
        return lsns;
    }

    /**
     * Runs the valid-event producer phase. Lifted from {@code SlotLagBoundedScenario} but
     * scoped to the {@code LabPoisonFlood} aggregate — kept verbatim so any rate-limiting
     * or seed-control behaviour stays identical across slot scenarios.
     */
    private long runProducer(EssentialsPerformanceLabProperties properties) throws InterruptedException {
        var phaseDuration = properties.getDuration();
        if (phaseDuration.isZero() || phaseDuration.isNegative()) return 0L;

        double producerRateHz       = Math.max(0.0d, properties.getProducerRateHz());
        long   perThreadIntervalNanos = producerRateHz <= 0.0d
                ? 0L
                : (long) (1_000_000_000.0d * Math.max(1, properties.getProducerThreads()) / producerRateHz);

        var nextEventNumber = new AtomicLong();
        var produced        = new AtomicLong();
        long deadlineNanos  = System.nanoTime() + phaseDuration.toNanos();

        var executor = Executors.newFixedThreadPool(properties.getProducerThreads(), r -> {
            var t = new Thread(r, "lab-poison-flood-producer");
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < properties.getProducerThreads(); i++) {
            final int producerIndex = i;
            executor.submit(() -> {
                var random       = new Random(properties.getRandomSeed() + (long) producerIndex * 31L);
                var cardinality  = Math.max(1, properties.getAggregateCardinality());
                long nextDueAt   = System.nanoTime();
                while (System.nanoTime() < deadlineNanos) {
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
                    var event = new LabPoisonFloodEvent(aggregateId, nextEventNumber.incrementAndGet(), System.nanoTime());
                    try {
                        unitOfWorkFactory.withUnitOfWork(() -> {
                            eventStore.appendToStream(ORDERS, aggregateId, List.of(event));
                            return null;
                        });
                        produced.incrementAndGet();
                    } catch (Exception e) {
                        if (!(e.getCause() instanceof OptimisticAppendToStreamException)) {
                            log.debug("append failed: {}", e.toString());
                        }
                    }
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(phaseDuration.toMillis() + TimeUnit.SECONDS.toMillis(5), TimeUnit.MILLISECONDS);
        if (!executor.isTerminated()) executor.shutdownNow();
        return produced.get();
    }

    private void writeMetricsIfConfigured(String metricsOutputFile, String json) throws IOException {
        if (!StringUtils.hasText(metricsOutputFile)) return;
        var target = Paths.get(metricsOutputFile).toAbsolutePath().normalize();
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, json + System.lineSeparator(),
                          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        log.info("Wrote poison-flood metrics to {}", target);
        System.out.println("############# [perf-lab] poison-flood metrics file: " + target);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize poison-flood metrics to JSON", e);
        }
    }

    public record PoisonFloodSnapshot(String capturedAt,
                                      String slotName,
                                      boolean cdcActive,
                                      long poisonInjected,
                                      long poisonRowsAtEnd,
                                      long receivedRowsAtEnd,
                                      long dispatchedRowsAtEnd,
                                      long producedValidEvents,
                                      long deliveredValidEvents,
                                      boolean poisonCountTracked,
                                      boolean validDeliveryRateOk,
                                      boolean receivedBacklogDrained,
                                      boolean dispatcherStillRunning,
                                      String verdict,
                                      CdcAvailability.Snapshot cdc) {
    }

    private record LabPoisonFloodEvent(String aggregateId, long sequence, long appendedAtNanos) {
    }
}
