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

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultClosingBooksScheduledScanProcessor<ID, AGGREGATE> implements ClosingBooksScheduledScanProcessor {
    private static final Logger log = LoggerFactory.getLogger(DefaultClosingBooksScheduledScanProcessor.class);

    private final AggregateType                                       aggregateType;
    private final ClosingBooksOpenGenerationRepository<ID>            generationRepository;
    private final ClosingBooksAggregateLoader<AGGREGATE>              aggregateLoader;
    private final ClosingBooksDecisionPolicy<ID, AGGREGATE>           policy;
    private final ClosingBooksCoordinator<ID>                         coordinator;
    private final ClosingBooksManagementMeasurementSupport            measurementSupport;

    public DefaultClosingBooksScheduledScanProcessor(AggregateType aggregateType,
                                                     ClosingBooksOpenGenerationRepository<ID> generationRepository,
                                                     ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                     ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                     ClosingBooksCoordinator<ID> coordinator) {
        this(aggregateType, generationRepository, aggregateLoader, policy, coordinator, Optional.empty());
    }

    public DefaultClosingBooksScheduledScanProcessor(AggregateType aggregateType,
                                                     ClosingBooksOpenGenerationRepository<ID> generationRepository,
                                                     ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                     ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                     ClosingBooksCoordinator<ID> coordinator,
                                                     Optional<MeterRegistry> meterRegistryOptional) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.aggregateLoader = requireNonNull(aggregateLoader, "No aggregateLoader provided");
        this.policy = requireNonNull(policy, "No policy provided");
        this.coordinator = requireNonNull(coordinator, "No coordinator provided");
        this.measurementSupport = new ClosingBooksManagementMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
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

        var openGenerations = measurementSupport.recordLoadOpenGenerations(aggregateType, batchSize,
                                                                           () -> generationRepository.loadOpenGenerations(aggregateType, batchSize));
        measurementSupport.recordLoadedBatchSize(aggregateType, openGenerations.size());
        int processedCount = 0;
        for (var generation : openGenerations) {
            try {
                var aggregate = aggregateLoader.load(generation.streamAggregateId());
                if (aggregate.isEmpty()) {
                    log.warn("Closing books scan skipped streamAggregateId '{}' for aggregateType '{}' because the aggregate could not be loaded",
                             generation.streamAggregateId(),
                             aggregateType);
                    measurementSupport.incrementProcessOutcome(aggregateType, "aggregate_missing");
                    continue;
                }

                var finalGeneration = generation;
                measurementSupport.recordProcessGeneration(aggregateType,
                                                           finalGeneration.generation(),
                                                           () -> applyDecision(finalGeneration, aggregate.get()));
                processedCount++;
            } catch (Exception e) {
                log.warn("Closing books scan failed for logicalAggregateId '{}' and aggregateType '{}'",
                         generation.logicalAggregateId(),
                         aggregateType,
                         e);
                measurementSupport.incrementProcessOutcome(aggregateType, "failed");
            }
        }
        return processedCount;
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
