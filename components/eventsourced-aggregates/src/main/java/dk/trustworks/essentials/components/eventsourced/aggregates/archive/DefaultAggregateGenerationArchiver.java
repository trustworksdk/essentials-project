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
import dk.trustworks.essentials.types.LongRange;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonBlank;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

public class DefaultAggregateGenerationArchiver implements AggregateGenerationArchiver {
    private static final Logger log = LoggerFactory.getLogger(DefaultAggregateGenerationArchiver.class);

    private final AggregateArchiveRegistry archiveRegistry;
    private final AggregateClosingBooksGenerationAccessProvider generationAccessProvider;
    private final ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore;
    private final AggregateArchiveExporter archiveExporter;
    private final AggregateArchiveDestination archiveDestination;
    private final AggregateArchiveMeasurementSupport measurementSupport;

    public DefaultAggregateGenerationArchiver(AggregateArchiveRegistry archiveRegistry,
                                              AggregateClosingBooksGenerationAccessProvider generationAccessProvider,
                                              ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                              AggregateArchiveExporter archiveExporter,
                                              AggregateArchiveDestination archiveDestination) {
        this(archiveRegistry,
             generationAccessProvider,
             eventStore,
             archiveExporter,
             archiveDestination,
             Optional.empty());
    }

    public DefaultAggregateGenerationArchiver(AggregateArchiveRegistry archiveRegistry,
                                              AggregateClosingBooksGenerationAccessProvider generationAccessProvider,
                                              ConfigurableEventStore<? extends AggregateEventStreamConfiguration> eventStore,
                                              AggregateArchiveExporter archiveExporter,
                                              AggregateArchiveDestination archiveDestination,
                                              Optional<MeterRegistry> meterRegistryOptional) {
        this.archiveRegistry = requireNonNull(archiveRegistry, "No archiveRegistry provided");
        this.generationAccessProvider = requireNonNull(generationAccessProvider, "No generationAccessProvider provided");
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
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

                // TODO Add explicit multi-node archive claiming/locking before export to avoid concurrent duplicate archive attempts.
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

                var aggregateConfiguration = eventStore.getAggregateEventStreamConfiguration(aggregateType);
                var deserializedStreamAggregateId = aggregateConfiguration.aggregateIdSerializer.deserialize(resolvedGeneration.streamAggregateId());
                var persistedEvents = eventStore.fetchStream(aggregateType, deserializedStreamAggregateId, LongRange.from(0L))
                                                .orElseThrow(() -> new IllegalStateException("Couldn't find event stream for closed generation '" + generation + "' using streamAggregateId '" + resolvedGeneration.streamAggregateId() + "'"))
                                                .eventList();

                var exportRequest = new AggregateArchiveExportRequest(aggregateType,
                                                                      logicalAggregateId,
                                                                      resolvedGeneration,
                                                                      persistedEvents);
                var archiveArtifact = archiveExporter.export(exportRequest);
                var archiveLocation = archiveDestination.write(new AggregateArchiveWriteRequest(aggregateType,
                                                                                               logicalAggregateId,
                                                                                               resolvedGeneration,
                                                                                               archiveArtifact));

                var archiveEntry = new AggregateArchiveEntry(aggregateType,
                                                             logicalAggregateId,
                                                             generation,
                                                             resolvedGeneration.streamAggregateId(),
                                                             AggregateArchiveStatus.ARCHIVED,
                                                             archiveArtifact.format(),
                                                             archiveLocation,
                                                             archiveArtifact.eventCount(),
                                                             archiveArtifact.checksum(),
                                                             resolvedGeneration.closedAt().orElse(null),
                                                             OffsetDateTime.now(),
                                                             null);
                archiveRegistry.save(archiveEntry);
                measurementSupport.recordArchivedEventCount(aggregateType, archiveArtifact.eventCount());
                measurementSupport.recordArchivedBytes(aggregateType, archiveArtifact.content().length);
                measurementSupport.incrementArchiveOutcome(aggregateType, "archived");
                log.info("Archived closed generation {} for aggregateType '{}' and logicalAggregateId '{}' to '{}' using format '{}' (events={}, bytes={}, checksum='{}')",
                         generation,
                         aggregateType,
                         logicalAggregateId,
                         archiveLocation,
                         archiveArtifact.format(),
                         archiveArtifact.eventCount(),
                         archiveArtifact.content().length,
                         archiveArtifact.checksum());
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
}
