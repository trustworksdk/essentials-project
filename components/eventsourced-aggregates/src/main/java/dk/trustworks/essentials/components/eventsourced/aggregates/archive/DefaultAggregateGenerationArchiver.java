/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

package dk.trustworks.essentials.components.eventsourced.aggregates.archive;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksGenerationAccessProvider;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The DefaultAggregateGenerationArchiver class provides a concrete implementation of the
 * {@link AggregateGenerationArchiver}
 * for archiving specific generations of aggregates. It handles the processing and storage of
 * aggregate archival data using various dependencies such as archive registry, event store,
 * and export/destination mechanisms.
 */
public class DefaultAggregateGenerationArchiver implements AggregateGenerationArchiver {
    private static final Logger log = LoggerFactory.getLogger(DefaultAggregateGenerationArchiver.class);

    private final AggregateArchiveRegistry archiveRegistry;
    private final AggregateClosingBooksGenerationAccessProvider generationAccessProvider;
    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private final AggregateArchiveExporter archiveExporter;
    private final AggregateArchiveDestination archiveDestination;
    private final AggregateArchiveMeasurementSupport measurementSupport;

    public DefaultAggregateGenerationArchiver(AggregateArchiveRegistry archiveRegistry,
                                              AggregateClosingBooksGenerationAccessProvider generationAccessProvider,
                                              ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                              HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                              AggregateArchiveExporter archiveExporter,
                                              AggregateArchiveDestination archiveDestination) {
        this(archiveRegistry,
             generationAccessProvider,
             eventStore,
             unitOfWorkFactory,
             archiveExporter,
             archiveDestination,
             Optional.empty());
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public DefaultAggregateGenerationArchiver(AggregateArchiveRegistry archiveRegistry,
                                              AggregateClosingBooksGenerationAccessProvider generationAccessProvider,
                                              ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                              HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                              AggregateArchiveExporter archiveExporter,
                                              AggregateArchiveDestination archiveDestination,
                                              Optional<MeterRegistry> meterRegistryOptional) {
        this.archiveRegistry = requireNonNull(archiveRegistry, "No archiveRegistry provided");
        this.generationAccessProvider = requireNonNull(generationAccessProvider, "No generationAccessProvider provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.archiveExporter = requireNonNull(archiveExporter, "No archiveExporter provided");
        this.archiveDestination = requireNonNull(archiveDestination, "No archiveDestination provided");
        this.measurementSupport = new AggregateArchiveMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
    }

    @Override
    public AggregateArchiveEntry archiveGeneration(AggregateType aggregateType,
                                                   String logicalAggregateId,
                                                   long generation) {
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonBlank(logicalAggregateId, "No logicalAggregateId provided");
        if (generation < 1) throw new IllegalArgumentException("generation must be >= 1");

        log.info("Starting archive of closed generation {} for aggregateType '{}' and logicalAggregateId '{}'",
                 generation,
                 aggregateType,
                 logicalAggregateId);

        try {
            return measurementSupport.recordArchiveGeneration(aggregateType, () -> {
                var existingEntry = archiveRegistry.findArchivedGeneration(aggregateType, logicalAggregateId, generation);
                if (existingEntry.isPresent() && existingEntry.get().status() == AggregateArchiveStatus.ARCHIVED) {
                    measurementSupport.incrementArchiveOutcome(aggregateType, "already_archived");
                    log.info("Skipping archive of generation {} for aggregateType '{}' and logicalAggregateId '{}' because it is already archived at '{}'",
                             generation,
                             aggregateType,
                             logicalAggregateId,
                             existingEntry.get().archiveLocation());
                    return existingEntry.get();
                }
                if (existingEntry.isPresent() && existingEntry.get().status() == AggregateArchiveStatus.IN_PROGRESS) {
                    measurementSupport.incrementArchiveOutcome(aggregateType, "in_progress_elsewhere");
                    log.info("Skipping archive of generation {} for aggregateType '{}' and logicalAggregateId '{}' because another worker is currently archiving it",
                             generation,
                             aggregateType,
                             logicalAggregateId);
                    return existingEntry.get();
                }

                var generationAccess = generationAccessProvider.resolve(aggregateType)
                                                               .orElseThrow(() -> new IllegalArgumentException("No closing-books generation access is registered for aggregateType '" + aggregateType + "'"));
                var resolvedGeneration = generationAccess.loadGenerations(logicalAggregateId)
                                                         .stream()
                                                         .filter(candidate -> candidate.generation() == generation)
                                                         .findFirst()
                                                         .orElseThrow(() -> new IllegalArgumentException("No generation '" + generation + "' exists for logicalAggregateId '" + logicalAggregateId + "' and aggregateType '" + aggregateType + "'"));
                if (!resolvedGeneration.isClosed()) {
                    throw new IllegalStateException("Generation '" + generation + "' for logicalAggregateId '" + logicalAggregateId + "' is still open and cannot be archived");
                }

                if (!archiveRegistry.tryClaim(aggregateType,
                                              logicalAggregateId,
                                              generation,
                                              resolvedGeneration.streamAggregateId(),
                                              OffsetDateTime.now())) {
                    measurementSupport.incrementArchiveOutcome(aggregateType, "claim_lost");
                    log.info("Skipping archive of generation {} for aggregateType '{}' and logicalAggregateId '{}' - another worker won the claim",
                             generation,
                             aggregateType,
                             logicalAggregateId);
                    return archiveRegistry.findArchivedGeneration(aggregateType, logicalAggregateId, generation)
                                          .orElseThrow(() -> new IllegalStateException("Lost archive claim race but no row exists"));
                }

                AggregateArchiveWriteResult writeResult;
                try {
                    writeResult = exportAndWrite(aggregateType, logicalAggregateId, resolvedGeneration);
                } catch (Exception e) {
                    var failedEntry = new AggregateArchiveEntry(aggregateType,
                                                                logicalAggregateId,
                                                                generation,
                                                                resolvedGeneration.streamAggregateId(),
                                                                AggregateArchiveStatus.FAILED,
                                                                archiveExporter.format(),
                                                                "n/a",
                                                                0L,
                                                                null,
                                                                resolvedGeneration.closedAt().orElse(null),
                                                                OffsetDateTime.now(),
                                                                e.getMessage());
                    archiveRegistry.save(failedEntry);
                    throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
                }

                var archiveEntry = new AggregateArchiveEntry(aggregateType,
                                                             logicalAggregateId,
                                                             generation,
                                                             resolvedGeneration.streamAggregateId(),
                                                             AggregateArchiveStatus.ARCHIVED,
                                                             archiveExporter.format(),
                                                             writeResult.locationUri(),
                                                             writeResult.recordsWritten(),
                                                             writeResult.checksum(),
                                                             resolvedGeneration.closedAt().orElse(null),
                                                             OffsetDateTime.now(),
                                                             null);
                archiveRegistry.save(archiveEntry);
                measurementSupport.recordArchivedEventCount(aggregateType, writeResult.recordsWritten());
                measurementSupport.recordArchivedBytes(aggregateType, writeResult.bytesWritten());
                measurementSupport.incrementArchiveOutcome(aggregateType, "archived");
                log.info("Archived closed generation {} for aggregateType '{}' and logicalAggregateId '{}' to '{}' using format '{}' (events={}, bytes={}, checksum='{}')",
                         generation,
                         aggregateType,
                         logicalAggregateId,
                         writeResult.locationUri(),
                         archiveExporter.format(),
                         writeResult.recordsWritten(),
                         writeResult.bytesWritten(),
                         writeResult.checksum());
                return archiveEntry;
            });
        } catch (RuntimeException e) {
            measurementSupport.incrementArchiveOutcome(aggregateType, "failed");
            log.warn("Failed to archive generation {} for aggregateType '{}' and logicalAggregateId '{}': {}",
                     generation,
                     aggregateType,
                     logicalAggregateId,
                     e.getMessage(),
                     e);
            throw e;
        }
    }

    /**
     * Reads the persisted events as a {@link java.util.stream.Stream} backed by the JDBI handle
     * of the surrounding {@link HandleAwareUnitOfWork} and streams them into the destination.
     * The UoW is held for the duration of the export so the cursor stays alive while the
     * exporter writes to the destination.
     */
    private AggregateArchiveWriteResult exportAndWrite(AggregateType aggregateType,
                                                       String logicalAggregateId,
                                                       dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateGeneration<String> resolvedGeneration) throws IOException {
        var aggregateConfiguration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
        var deserializedStreamAggregateId = aggregateConfiguration.aggregateIdSerializer.deserialize(resolvedGeneration.streamAggregateId());
        var writeRequest = new AggregateArchiveWriteRequest(aggregateType,
                                                            logicalAggregateId,
                                                            resolvedGeneration,
                                                            archiveExporter.format(),
                                                            archiveExporter.fileExtension());
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var aggregateEventStream = eventStore.fetchStream(aggregateType, deserializedStreamAggregateId, LongRange.from(0L))
                                                 .orElseThrow(() -> new IllegalStateException("Couldn't find event stream for closed generation '" + resolvedGeneration.generation() + "' using streamAggregateId '" + resolvedGeneration.streamAggregateId() + "'"));
            var exportRequest = new AggregateArchiveExportRequest(aggregateType,
                                                                  logicalAggregateId,
                                                                  resolvedGeneration,
                                                                  aggregateEventStream.events());
            return archiveDestination.write(writeRequest, out -> archiveExporter.export(exportRequest, out));
        });
    }
}
