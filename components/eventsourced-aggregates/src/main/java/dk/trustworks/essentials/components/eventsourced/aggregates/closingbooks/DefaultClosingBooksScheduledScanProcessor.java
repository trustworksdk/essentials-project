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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Default implementation of the {@link ClosingBooksScheduledScanProcessor} interface.
 * This class is designed to handle the scheduled scanning of open generations of aggregates
 * in an event-sourced system and apply policies for closing books and opening new generations.
 *
 * @param <ID>        the type of the identifier for aggregate generations
 * @param <AGGREGATE> the type of the aggregate being processed
 */
public class DefaultClosingBooksScheduledScanProcessor<ID, AGGREGATE> implements ClosingBooksScheduledScanProcessor {
    private static final Logger log = LoggerFactory.getLogger(DefaultClosingBooksScheduledScanProcessor.class);

    /**
     * Default {@code scanRetryDelay}. Long enough that a permanently broken generation costs almost nothing, short
     * enough that a generation deferred because of a transient failure still rolls close to its boundary.
     */
    public static final Duration DEFAULT_SCAN_RETRY_DELAY = Duration.ofMinutes(5);

    private final AggregateType                                       aggregateType;
    private final ClosingBooksOpenGenerationRepository<ID>            generationRepository;
    private final ClosingBooksAggregateLoader<AGGREGATE>              aggregateLoader;
    private final ClosingBooksDecisionPolicy<ID, AGGREGATE>           policy;
    private final ClosingBooksCoordinator<ID>                         coordinator;
    private final ClosingBooksManagementMeasurementSupport            measurementSupport;
    private final Clock                                               clock;
    private final Duration                                            scanRetryDelay;

    /**
     * Constructs a DefaultClosingBooksScheduledScanProcessor instance with the specified components.
     * This processor schedules and manages the scanning of closing book aggregates.
     *
     * @param aggregateType the type of aggregate being processed
     * @param generationRepository the repository responsible for accessing open generations of closing books
     * @param aggregateLoader the loader used to load the aggregate data
     * @param policy the decision policy applied to the aggregates during processing
     * @param coordinator the coordinator responsible for managing the overall processing workflow
     */
    public DefaultClosingBooksScheduledScanProcessor(AggregateType aggregateType,
                                                     ClosingBooksOpenGenerationRepository<ID> generationRepository,
                                                     ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                     ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                     ClosingBooksCoordinator<ID> coordinator) {
        this(aggregateType, generationRepository, aggregateLoader, policy, coordinator, Optional.empty());
    }

    /**
     * Constructs a DefaultClosingBooksScheduledScanProcessor instance with the specified components.
     * This processor schedules and manages the scanning of closing book aggregates.
     *
     * @param aggregateType the type of aggregate being processed
     * @param generationRepository the repository responsible for accessing open generations of closing books
     * @param aggregateLoader the loader used to load the aggregate data
     * @param policy the decision policy applied to the aggregates during processing
     * @param coordinator the coordinator responsible for managing the overall processing workflow
     * @param meterRegistryOptional an optional registry used to track and measure processing metrics
     */
    public DefaultClosingBooksScheduledScanProcessor(AggregateType aggregateType,
                                                     ClosingBooksOpenGenerationRepository<ID> generationRepository,
                                                     ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                     ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                     ClosingBooksCoordinator<ID> coordinator,
                                                     Optional<MeterRegistry> meterRegistryOptional) {
        this(aggregateType,
             generationRepository,
             aggregateLoader,
             policy,
             coordinator,
             meterRegistryOptional,
             Clock.systemUTC(),
             DEFAULT_SCAN_RETRY_DELAY);
    }

    /**
     * Constructs a DefaultClosingBooksScheduledScanProcessor instance with the specified components.
     *
     * @param aggregateType the type of aggregate being processed
     * @param generationRepository the repository responsible for accessing open generations of closing books
     * @param aggregateLoader the loader used to load the aggregate data
     * @param policy the decision policy applied to the aggregates during processing
     * @param coordinator the coordinator responsible for managing the overall processing workflow
     * @param meterRegistryOptional an optional registry used to track and measure processing metrics
     * @param clock the clock used to derive the scan-eligibility cut-off and deferral deadlines
     * @param scanRetryDelay how long a generation the scan could not process is skipped for, so that one broken
     *                       generation costs one attempt per window instead of the whole batch on every poll
     */
    public DefaultClosingBooksScheduledScanProcessor(AggregateType aggregateType,
                                                     ClosingBooksOpenGenerationRepository<ID> generationRepository,
                                                     ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                     ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                     ClosingBooksCoordinator<ID> coordinator,
                                                     Optional<MeterRegistry> meterRegistryOptional,
                                                     Clock clock,
                                                     Duration scanRetryDelay) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.aggregateLoader = requireNonNull(aggregateLoader, "No aggregateLoader provided");
        this.policy = requireNonNull(policy, "No policy provided");
        this.coordinator = requireNonNull(coordinator, "No coordinator provided");
        this.measurementSupport = new ClosingBooksManagementMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
        this.clock = requireNonNull(clock, "No clock provided");
        this.scanRetryDelay = requireNonNull(scanRetryDelay, "No scanRetryDelay provided");
        if (scanRetryDelay.isNegative()) {
            throw new IllegalArgumentException("scanRetryDelay must be >= 0");
        }
    }

    @Override
    public AggregateType aggregateType() {
        return aggregateType;
    }

    @Override
    public int processNextBatch(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }

        var now = OffsetDateTime.now(clock);
        var openGenerations = measurementSupport.recordLoadOpenGenerations(aggregateType, batchSize,
                                                                           () -> generationRepository.loadOpenGenerations(aggregateType, batchSize, now));
        measurementSupport.recordLoadedBatchSize(aggregateType, openGenerations.size());
        int processedCount = 0;
        for (var generation : openGenerations) {
            try {
                var aggregate = aggregateLoader.load(generation.streamAggregateId());
                if (aggregate.isEmpty()) {
                    log.warn("Closing books scan skipped streamAggregateId '{}' for aggregateType '{}' because the aggregate could not be loaded — deferring it for {}",
                             generation.streamAggregateId(),
                             aggregateType,
                             scanRetryDelay);
                    measurementSupport.incrementProcessOutcome(aggregateType, "aggregate_missing");
                    deferScan(generation, now);
                    continue;
                }

                var finalGeneration = generation;
                measurementSupport.recordProcessGeneration(aggregateType,
                                                           finalGeneration.generation(),
                                                           () -> applyDecision(finalGeneration, aggregate.get()));
                processedCount++;
            } catch (Exception e) {
                log.warn("Closing books scan failed for logicalAggregateId '{}' and aggregateType '{}' — deferring it for {}",
                         generation.logicalAggregateId(),
                         aggregateType,
                         scanRetryDelay,
                         e);
                measurementSupport.incrementProcessOutcome(aggregateType, "failed");
                deferScan(generation, now);
            }
        }
        return processedCount;
    }

    /**
     * Push a generation the scan could not process out of the next batches.
     * <p>
     * A batch is the oldest {@code batchSize} open generations, so without this a generation that keeps failing stays
     * at the head of every batch and starves every other aggregate of the same type. Deferring is best-effort: if it
     * fails the scan carries on, having lost only the protection for this one generation.
     */
    private void deferScan(AggregateGeneration<ID> generation, OffsetDateTime now) {
        try {
            generationRepository.deferScan(aggregateType,
                                           generation.logicalAggregateId(),
                                           now.plus(scanRetryDelay));
        } catch (Exception e) {
            log.warn("Could not defer the closing books scan of logicalAggregateId '{}' for aggregateType '{}'",
                     generation.logicalAggregateId(),
                     aggregateType,
                     e);
        }
    }

    private void applyDecision(AggregateGeneration<ID> generation,
                               AGGREGATE aggregate) {
        var resultingGeneration = coordinator.evaluatePolicy(generation.logicalAggregateId(),
                                                             aggregate,
                                                             ClosingBooksTriggerMode.SCHEDULED_SCAN,
                                                             policy);
        if (resultingGeneration.generation() > generation.generation()) {
            log.info("Closing books scan rolled aggregateType '{}' logicalAggregateId '{}' from generation '{}' to '{}'",
                     aggregateType,
                     generation.logicalAggregateId(),
                     generation.generation(),
                     resultingGeneration.generation());
            measurementSupport.incrementProcessOutcome(aggregateType, "close_and_open_next");
        } else if (resultingGeneration.isClosed()) {
            log.info("Closing books scan closed aggregateType '{}' logicalAggregateId '{}' generation '{}'",
                     aggregateType,
                     generation.logicalAggregateId(),
                     generation.generation());
            measurementSupport.incrementProcessOutcome(aggregateType, "close_only");
        } else {
            measurementSupport.incrementProcessOutcome(aggregateType, "keep_open");
        }
    }
}
