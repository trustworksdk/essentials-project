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
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkException;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWorkStatus;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.MessageFormatter.msg;

/**
 * Coordinates generation lifecycle transitions for one {@link AggregateType}.
 * <p>
 * The coordinator encapsulates the common closing-books flow of resolving the current generation,
 * opening the first generation on demand, and rolling from one generation to the next.
 */
public class ClosingBooksCoordinator<ID> {
    private final AggregateType                                                       aggregateType;
    private final ClosingBooksGenerationRepository<ID>                                generationRepository;
    private final ClosingBooksStreamIdGenerator<ID>                                   streamIdGenerator;
    private final HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork>       unitOfWorkFactory;
    private final Clock                                                               clock;
    private final ClosingBooksManagementMeasurementSupport                            measurementSupport;

    /**
     * Constructs a new instance of {@code ClosingBooksCoordinator}.
     *
     * @param aggregateType The type of the aggregate being coordinated.
     * @param generationRepository The repository for managing closing-book generations.
     * @param streamIdGenerator The generator for creating unique stream IDs for aggregate instances.
     * @param unitOfWorkFactory The factory for creating instances of {@code HandleAwareUnitOfWork}.
     */
    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        this(aggregateType,
             generationRepository,
             streamIdGenerator,
             unitOfWorkFactory,
             Clock.systemUTC());
    }

    public ClosingBooksCoordinator(AggregateType aggregateType,
                                   ClosingBooksGenerationRepository<ID> generationRepository,
                                   ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                                   HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                   Clock clock) {
        this(aggregateType,
             generationRepository,
             streamIdGenerator,
             unitOfWorkFactory,
             clock,
             Optional.empty());
    }

    /**
     * Constructs a new instance of {@code ClosingBooksCoordinator} that reports rollover metrics.
     *
     * @param aggregateType         The type of the aggregate being coordinated.
     * @param generationRepository  The repository for managing closing-book generations.
     * @param streamIdGenerator     The generator for creating unique stream IDs for aggregate instances.
     * @param unitOfWorkFactory     The factory for creating instances of {@code HandleAwareUnitOfWork}.
     * @param clock                 The clock used to timestamp policy evaluations and rollovers.
     * @param meterRegistryOptional Optional Micrometer registry. When empty, no metrics are recorded.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    ClosingBooksCoordinator(AggregateType aggregateType,
                            ClosingBooksGenerationRepository<ID> generationRepository,
                            ClosingBooksStreamIdGenerator<ID> streamIdGenerator,
                            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                            Clock clock,
                            Optional<MeterRegistry> meterRegistryOptional) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        this.generationRepository = requireNonNull(generationRepository, "No generationRepository provided");
        this.streamIdGenerator = requireNonNull(streamIdGenerator, "No streamIdGenerator provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.clock = requireNonNull(clock, "No clock provided");
        this.measurementSupport = new ClosingBooksManagementMeasurementSupport(requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided"));
    }

    /**
     * Resolve the current open generation or open generation {@code 1} if no generation exists yet.
     */
    public java.util.Optional<AggregateGeneration<ID>> resolveCurrentGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId);
    }

    /**
     * Resolve all known generations for the provided logical aggregate id.
     */
    public java.util.List<AggregateGeneration<ID>> loadGenerations(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.loadGenerations(aggregateType, logicalAggregateId);
    }

    /**
     * Resolve the current open generation or open generation {@code 1} if no generation exists yet.
     */
    public AggregateGeneration<ID> resolveOrOpenCurrentGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                   .orElseGet(() -> generationRepository.withGenerationLock(aggregateType,
                                                                                            logicalAggregateId,
                                                                                            // Re-resolved under the lock: another caller may have opened the
                                                                                            // first generation between the resolve above and this point.
                                                                                            () -> generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                                                                                                      .orElseGet(() -> openFirstGeneration(logicalAggregateId))));
    }

    /**
     * Close the current generation and immediately open the next generation, atomically.
     * <p>
     * The close + open pair runs inside a single {@link HandleAwareUnitOfWork}: a crash or
     * exception between the two repository calls rolls back the close, leaving the previous
     * generation {@code OPEN} for safe retry rather than stranding the aggregate with no open
     * generation.
     * <p>
     * This closes whichever generation is open once the generation lock is held. A caller that decided to roll on the
     * strength of an aggregate it loaded before taking the lock should use
     * {@link #closeAndOpenNextGeneration(AggregateGeneration)}, which refuses when that generation is no longer the open one.
     *
     * @throws IllegalStateException if no open generation exists
     */
    public AggregateGeneration<ID> closeAndOpenNextGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        return closeAndOpenNext(logicalAggregateId, null);
    }

    /**
     * Close {@code expectedCurrentGeneration} and immediately open the next generation, atomically - but only while it
     * is still the open generation of its logical aggregate.
     * <p>
     * The guarded form of {@link #closeAndOpenNextGeneration(LogicalAggregateId)}: under the generation lock and in the
     * same unit of work, the open generation is compared with {@code expectedCurrentGeneration} by stream id before
     * anything is closed. When another caller rolled or closed it in between, nothing is closed or opened and
     * {@link ClosingBooksGenerationMovedException} is thrown; the rollover is then counted with outcome
     * {@code generation_moved}, not {@code failed}. The comparison uses the open generation that the unguarded form
     * resolves anyway, so it costs no extra database round trip.
     *
     * @param expectedCurrentGeneration the generation the caller loaded its aggregate from
     * @return the newly opened generation
     * @throws ClosingBooksGenerationMovedException if {@code expectedCurrentGeneration} is no longer the open generation
     */
    public AggregateGeneration<ID> closeAndOpenNextGeneration(AggregateGeneration<ID> expectedCurrentGeneration) {
        requireNonNull(expectedCurrentGeneration, "No expectedCurrentGeneration provided");
        return closeAndOpenNext(expectedCurrentGeneration.logicalAggregateId(), expectedCurrentGeneration.streamAggregateId());
    }

    /**
     * Close the open generation and open the next one. When {@code expectedStreamAggregateId} is not {@code null}, refuse
     * with {@link ClosingBooksGenerationMovedException} unless the open generation has that stream id.
     */
    AggregateGeneration<ID> closeAndOpenNext(LogicalAggregateId<ID> logicalAggregateId, String expectedStreamAggregateId) {
        // The counters are incremented after the UnitOfWork returns, never inside it: a rollback must not leave a
        // count claiming a generation was closed that the database then rolled back.
        try {
            var nextGeneration = measurementSupport.recordRollover(aggregateType,
                                                                    () -> generationRepository.withGenerationLock(aggregateType, logicalAggregateId, () -> unitOfWorkFactory.withUnitOfWork(uow -> {
                                                                        var openGeneration = generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId);
                                                                        if (expectedStreamAggregateId != null) {
                                                                            requireStillOpen(logicalAggregateId, expectedStreamAggregateId, openGeneration);
                                                                        }
                                                                        openGeneration.orElseThrow(() -> new IllegalStateException("No open generation exists for logicalAggregateId '" + logicalAggregateId + "'"));
                                                                        generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
                                                                        // The repository decides the next generation number and feeds it to the generator, so the stream id it
                                                                        // persists always names the generation on the row it is stored with.
                                                                        return generationRepository.openNextGeneration(aggregateType,
                                                                                                                       logicalAggregateId,
                                                                                                                       streamIdGenerator);
                                                                    })));
            measurementSupport.incrementGenerationsClosed(aggregateType);
            measurementSupport.incrementGenerationsOpened(aggregateType);
            measurementSupport.incrementRolloverOutcome(aggregateType, "succeeded");
            measurementSupport.recordRolloverTimestamp(aggregateType, clock.millis());
            return nextGeneration;
        } catch (RuntimeException e) {
            var generationMoved = generationMovedWrappedIn(e);
            if (generationMoved != null) {
                measurementSupport.incrementRolloverOutcome(aggregateType, "generation_moved");
                throw generationMoved;
            }
            measurementSupport.incrementRolloverOutcome(aggregateType, "failed");
            throw e;
        }
    }

    /**
     * The {@link ClosingBooksGenerationMovedException} that {@code e} is, or that the unit of work wrapped in it:
     * {@code UnitOfWorkFactory.withUnitOfWork} rethrows whatever its function throws as a {@link UnitOfWorkException},
     * whether it created the unit of work or joined one, and the PostgreSQL generation lock runs in one as well. Only
     * {@link UnitOfWorkException} layers are unwrapped, so a refusal some other exception carries as its cause is not
     * taken for this operation's own. Answers {@code null} when there is none.
     */
    private static ClosingBooksGenerationMovedException generationMovedWrappedIn(RuntimeException e) {
        Throwable candidate = e;
        // Throwable.getCause() answers null for an exception that is its own cause, so this loop always ends
        while (candidate instanceof UnitOfWorkException && candidate.getCause() != null) {
            candidate = candidate.getCause();
        }
        return candidate instanceof ClosingBooksGenerationMovedException generationMoved ? generationMoved : null;
    }

    /**
     * Throw {@link ClosingBooksGenerationMovedException} unless {@code openGeneration} is the generation with
     * {@code expectedStreamAggregateId}. The caller holds the generation lock.
     */
    private void requireStillOpen(LogicalAggregateId<ID> logicalAggregateId,
                                  String expectedStreamAggregateId,
                                  Optional<AggregateGeneration<ID>> openGeneration) {
        if (openGeneration.isEmpty() || !openGeneration.get().streamAggregateId().equals(expectedStreamAggregateId)) {
            throw new ClosingBooksGenerationMovedException(logicalAggregateId, expectedStreamAggregateId, openGeneration.orElse(null));
        }
    }

    /**
     * Evaluate a closing-books policy against the current generation and aggregate instance.
     * <p>
     * As with the overload taking a rollover, nothing checks that {@code aggregate} belongs to the generation that is open
     * once the generation lock is held; see there, and
     * {@link #evaluatePolicy(AggregateGeneration, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)}
     * for the guarded form.
     */
    public <AGGREGATE> AggregateGeneration<ID> evaluatePolicy(LogicalAggregateId<ID> logicalAggregateId,
                                                              AGGREGATE aggregate,
                                                              ClosingBooksTriggerMode triggerMode,
                                                              ClosingBooksDecisionPolicy<ID, AGGREGATE> policy) {
        return evaluatePolicy(logicalAggregateId, aggregate, triggerMode, policy, null);
    }

    /**
     * Evaluate a closing-books policy, rolling with the application's own rollover on {@code CLOSE_AND_OPEN_NEXT}.
     * <p>
     * The policy and the rollover receive {@code aggregate} as given, with the generation that is open once the
     * generation lock is held; nothing checks that the two belong together. Load the aggregate from that generation,
     * in the unit of work this is called in - the rollover joins it - or a closing event the rollover records on the
     * aggregate is not persisted. The scheduled scan does both for you.
     * <p>
     * So this is safe only when {@code aggregate} cannot belong to an older generation: the caller loaded it while
     * already holding the generation lock, or the policy can only answer {@code KEEP_OPEN}. Otherwise - an aggregate
     * loaded before the call, as on access or in a custom {@link ClosingBooksScheduledScanProcessor} - a generation
     * another caller rolled in between would be closed on the strength of an older generation's aggregate, and the
     * rollover would build the next generation from that older state. Use
     * {@link #evaluatePolicy(AggregateGeneration, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)}
     * there, which refuses instead.
     *
     * @param rollover performs the rollover - the aggregate's closing event and its next generation, through the
     *                 application's next-generation factory (EF-1b) - inside the generation lock and a unit of work. With
     *                 {@code null}, {@code CLOSE_AND_OPEN_NEXT} rolls only the generation row, as
     *                 {@link #closeAndOpenNextGeneration(LogicalAggregateId)} does
     * @return the generation that is current afterwards, or the closed one if none is open
     */
    public <AGGREGATE> AggregateGeneration<ID> evaluatePolicy(LogicalAggregateId<ID> logicalAggregateId,
                                                              AGGREGATE aggregate,
                                                              ClosingBooksTriggerMode triggerMode,
                                                              ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                              ClosingBooksScanRollover<ID, AGGREGATE> rollover) {
        requireNonNull(logicalAggregateId, "No logicalAggregateId provided");
        requireNonNull(aggregate, "No aggregate provided");
        requireNonNull(triggerMode, "No triggerMode provided");
        requireNonNull(policy, "No policy provided");

        // The whole evaluation is one critical section: the policy decides against the generation resolved here, and
        // acting on a generation another caller has meanwhile closed or rolled would either fail outright or close a
        // generation the policy never saw. Both lock implementations are reentrant, so the nested acquisition in
        // closeAndOpenNextGeneration is a no-op.
        return generationRepository.withGenerationLock(aggregateType, logicalAggregateId,
                                                       () -> decide(resolveOrOpenCurrentGeneration(logicalAggregateId), aggregate, triggerMode, policy, rollover));
    }

    /**
     * Evaluate a closing-books policy for an aggregate loaded from {@code loadedFrom}, but only while that is still the
     * open generation of its logical aggregate.
     * <p>
     * The guarded form of
     * {@link #evaluatePolicy(LogicalAggregateId, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)}:
     * under the generation lock the open generation is compared with {@code loadedFrom} by stream id. When another caller
     * rolled or closed it in between, the policy and the rollover are not called, nothing is closed or opened, and
     * {@link ClosingBooksGenerationMovedException} is thrown. Otherwise the policy and the rollover receive
     * {@code aggregate} with {@code loadedFrom}'s generation, exactly as the unguarded form would. The comparison uses the
     * open generation that the unguarded form resolves anyway, so it costs no extra database round trip.
     * <p>
     * Unlike the unguarded form this never opens a first generation: {@code loadedFrom} was open when the aggregate was
     * loaded, so an empty logical aggregate means it was closed since. As there, load the aggregate in the unit of work
     * this is called in, or a closing event the rollover records on it is not persisted.
     *
     * @param loadedFrom  the generation {@code aggregate} was loaded from
     * @param aggregate   the aggregate loaded from {@code loadedFrom}'s stream
     * @param triggerMode what triggered the evaluation
     * @param policy      the decision policy
     * @param rollover    performs the rollover on {@code CLOSE_AND_OPEN_NEXT}, inside the generation lock and a unit of work;
     *                    with {@code null} only the generation row is rolled
     * @return the generation that is current afterwards, or the closed one if none is open
     * @throws ClosingBooksGenerationMovedException if {@code loadedFrom} is no longer the open generation
     */
    public <AGGREGATE> AggregateGeneration<ID> evaluatePolicy(AggregateGeneration<ID> loadedFrom,
                                                              AGGREGATE aggregate,
                                                              ClosingBooksTriggerMode triggerMode,
                                                              ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                              ClosingBooksScanRollover<ID, AGGREGATE> rollover) {
        requireNonNull(loadedFrom, "No loadedFrom provided");
        requireNonNull(aggregate, "No aggregate provided");
        requireNonNull(triggerMode, "No triggerMode provided");
        requireNonNull(policy, "No policy provided");

        var logicalAggregateId = loadedFrom.logicalAggregateId();
        try {
            return generationRepository.withGenerationLock(aggregateType, logicalAggregateId, () -> {
                var openGeneration = generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId);
                requireStillOpen(logicalAggregateId, loadedFrom.streamAggregateId(), openGeneration);
                return decide(openGeneration.get(), aggregate, triggerMode, policy, rollover);
            });
        } catch (RuntimeException e) {
            // The PostgreSQL generation lock runs in a unit of work, which wraps the refusal.
            var generationMoved = generationMovedWrappedIn(e);
            throw generationMoved != null ? generationMoved : e;
        }
    }

    /**
     * What {@link #evaluateScannedGeneration} did with the generation a scheduled scan read.
     */
    enum ScanOutcome {
        /** The policy decided on the generation the scan read; {@link ScanEvaluation#generation()} is current afterwards. */
        DECIDED,
        /**
         * The generation the scan read was no longer the open one once the generation lock was held (it was rolled or
         * closed in between); nothing was loaded or decided. {@link ScanEvaluation#generation()} is the open
         * generation, or the scanned one when none is open.
         */
        GENERATION_MOVED,
        /** The aggregate loader found no aggregate for the generation's stream; nothing was decided. */
        AGGREGATE_MISSING
    }

    /**
     * The result of {@link #evaluateScannedGeneration}.
     */
    record ScanEvaluation<ID>(ScanOutcome outcome, AggregateGeneration<ID> generation) {
    }

    /**
     * Evaluate a closing-books policy for a generation a scheduled scan read from its batch.
     * <p>
     * Unlike {@link #evaluatePolicy(LogicalAggregateId, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)},
     * which takes an aggregate the caller already loaded, this loads the aggregate itself, inside the generation lock
     * and in the unit of work the decision and the rollover run in. So:
     * <ul>
     *     <li>the aggregate the policy and the rollover see always belongs to the generation in the context - a
     *     generation rolled or closed after the scan read it is left alone ({@link ScanOutcome#GENERATION_MOVED}),
     *     instead of being closed on the strength of an older generation's aggregate;</li>
     *     <li>the aggregate is registered with the unit of work that commits the rollover, so a closing event the
     *     rollover records on it is persisted with the rollover.</li>
     * </ul>
     *
     * @param scannedGeneration the generation as the scan read it
     * @param aggregateLoader   loads the aggregate of the generation's stream; called at most once, under the lock. When it
     *                          returns an aggregate but leaves the unit of work rollback-only (it caught an exception from
     *                          a nested {@code withUnitOfWork}), nothing is decided and the call fails with an exception
     *                          whose cause chain holds an {@link IllegalStateException} naming the loader
     * @param policy            the decision policy
     * @param rollover          the application's rollover for {@code CLOSE_AND_OPEN_NEXT}, or {@code null} to roll only
     *                          the generation row
     */
    <AGGREGATE> ScanEvaluation<ID> evaluateScannedGeneration(AggregateGeneration<ID> scannedGeneration,
                                                             ClosingBooksAggregateLoader<AGGREGATE> aggregateLoader,
                                                             ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                             ClosingBooksScanRollover<ID, AGGREGATE> rollover) {
        requireNonNull(scannedGeneration, "No scannedGeneration provided");
        requireNonNull(aggregateLoader, "No aggregateLoader provided");
        requireNonNull(policy, "No policy provided");

        var logicalAggregateId = scannedGeneration.logicalAggregateId();
        // On PostgreSQL the lock already runs in a unit of work and this joins it; the in-memory lock has none, so this
        // is the one the load, the decision and the rollover share.
        return generationRepository.withGenerationLock(aggregateType, logicalAggregateId, () -> unitOfWorkFactory.withUnitOfWork(uow -> {
            var currentGeneration = generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId);
            if (currentGeneration.isEmpty() || currentGeneration.get().generation() != scannedGeneration.generation()) {
                return new ScanEvaluation<>(ScanOutcome.GENERATION_MOVED, currentGeneration.orElse(scannedGeneration));
            }
            var aggregate = aggregateLoader.load(currentGeneration.get().streamAggregateId());
            if (aggregate.isEmpty()) {
                return new ScanEvaluation<>(ScanOutcome.AGGREGATE_MISSING, currentGeneration.get());
            }
            if (uow.status() == UnitOfWorkStatus.MarkedForRollbackOnly) {
                // A nested withUnitOfWork inside the loader threw and the loader caught it: the unit of work this shares
                // can no longer commit, so a decision or a rollover made in it would be rolled back unseen.
                throw new IllegalStateException(msg("The closing books aggregateLoader for aggregateType '{}' left the scan's unit of work marked rollback-only while loading streamAggregateId '{}' - a loader must report a missing aggregate by returning Optional.empty(), not by catching an exception from a unit of work it joined",
                                                    aggregateType,
                                                    currentGeneration.get().streamAggregateId()),
                                                uow.getCauseOfRollback());
            }
            return new ScanEvaluation<>(ScanOutcome.DECIDED,
                                        decide(currentGeneration.get(), aggregate.get(), ClosingBooksTriggerMode.SCHEDULED_SCAN, policy, rollover));
        }));
    }

    /**
     * Decide on {@code currentGeneration} and act on the decision. The caller holds the generation lock.
     */
    private <AGGREGATE> AggregateGeneration<ID> decide(AggregateGeneration<ID> currentGeneration,
                                                       AGGREGATE aggregate,
                                                       ClosingBooksTriggerMode triggerMode,
                                                       ClosingBooksDecisionPolicy<ID, AGGREGATE> policy,
                                                       ClosingBooksScanRollover<ID, AGGREGATE> rollover) {
        var logicalAggregateId = currentGeneration.logicalAggregateId();
        var context = new ClosingBooksEvaluationContext<>(aggregateType,
                                                          logicalAggregateId,
                                                          currentGeneration,
                                                          aggregate,
                                                          triggerMode,
                                                          OffsetDateTime.now(clock));
        var decision = policy.decide(context);

        measurementSupport.incrementPolicyDecision(aggregateType, decision, triggerMode);

        return switch (decision) {
            case KEEP_OPEN -> currentGeneration;
            case CLOSE_ONLY -> {
                var closed = generationRepository.closeCurrentGeneration(aggregateType, logicalAggregateId);
                measurementSupport.incrementGenerationsClosed(aggregateType);
                yield closed;
            }
            // Counts its own generations_closed / generations_opened, so nothing is added here.
            case CLOSE_AND_OPEN_NEXT -> rollover == null
                                        ? closeAndOpenNextGeneration(logicalAggregateId)
                                        : rollWith(rollover, context);
        };
    }

    /**
     * The application's rollover, in one unit of work under the generation lock already held: its repository's
     * {@code closeAndOpenNextGeneration} re-enters both, and it joins the caller's unit of work when there is one (the
     * scan's, which loaded the aggregate). Answers the generation current afterwards.
     */
    private <AGGREGATE> AggregateGeneration<ID> rollWith(ClosingBooksScanRollover<ID, AGGREGATE> rollover,
                                                         ClosingBooksEvaluationContext<ID, AGGREGATE> context) {
        unitOfWorkFactory.usingUnitOfWork(uow -> rollover.rollover(context));
        var logicalAggregateId = context.logicalAggregateId();
        return generationRepository.resolveCurrentGeneration(aggregateType, logicalAggregateId)
                                   .orElseGet(() -> generationRepository.loadGenerations(aggregateType, logicalAggregateId).getLast());
    }

    private AggregateGeneration<ID> openFirstGeneration(LogicalAggregateId<ID> logicalAggregateId) {
        // "First" only in the sense of the first one currently open: closed generations may already exist, and the
        // repository numbers past them. It used to load every generation here to work that number out for the stream
        // id, duplicating the repository's own rule; the repository now supplies it to the generator instead.
        var generation = unitOfWorkFactory.withUnitOfWork(uow -> generationRepository.openNextGeneration(aggregateType,
                                                                                                        logicalAggregateId,
                                                                                                        streamIdGenerator));
        measurementSupport.incrementGenerationsOpened(aggregateType);
        return generation;
    }

    /**
     * Creates a builder for a {@link ClosingBooksCoordinator}.
     *
     * @param <ID> the aggregate id type
     * @return a new builder
     */
    public static <ID> Builder<ID> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link ClosingBooksCoordinator}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder<ID> {
        private AggregateType aggregateType;
        private ClosingBooksGenerationRepository<ID> generationRepository;
        private ClosingBooksStreamIdGenerator<ID> streamIdGenerator;
        private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
        private Clock clock;
        private MeterRegistry meterRegistryOptional;

        /**
         * @param aggregateType required
         * @return this builder
         */
        public Builder<ID> setAggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        /**
         * @param generationRepository required
         * @return this builder
         */
        public Builder<ID> setGenerationRepository(ClosingBooksGenerationRepository<ID> generationRepository) {
            this.generationRepository = generationRepository;
            return this;
        }

        /**
         * @param streamIdGenerator required
         * @return this builder
         */
        public Builder<ID> setStreamIdGenerator(ClosingBooksStreamIdGenerator<ID> streamIdGenerator) {
            this.streamIdGenerator = streamIdGenerator;
            return this;
        }

        /**
         * @param unitOfWorkFactory required
         * @return this builder
         */
        public Builder<ID> setUnitOfWorkFactory(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
            return this;
        }

        /**
         * @param clock required
         * @return this builder
         */
        public Builder<ID> setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * @param meterRegistryOptional optional — {@code null} selects the default
         * @return this builder
         */
        public Builder<ID> setMeterRegistry(MeterRegistry meterRegistryOptional) {
            this.meterRegistryOptional = meterRegistryOptional;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setMeterRegistry}.
         *
         * @param meterRegistryOptional the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder<ID> setMeterRegistry(Optional<MeterRegistry> meterRegistryOptional) {
            requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
            return setMeterRegistry(meterRegistryOptional.orElse(null));
        }

        /**
         * @return the new {@link ClosingBooksCoordinator}
         */
        public ClosingBooksCoordinator<ID> build() {
            return new ClosingBooksCoordinator<>(aggregateType,
                                                   generationRepository,
                                                   streamIdGenerator,
                                                   unitOfWorkFactory,
                                                   clock,
                                                   Optional.ofNullable(meterRegistryOptional));
        }
    }

}
