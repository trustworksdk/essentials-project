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

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Thrown by the guarded closing-books operations when the generation the caller loaded its aggregate from is no longer
 * the open generation of the logical aggregate once the generation lock is held: another caller rolled or closed it in
 * between. Nothing was closed, opened or decided.
 * <p>
 * The guarded operations are
 * {@link ClosingBooksCoordinator#closeAndOpenNextGeneration(AggregateGeneration)},
 * {@link ClosingBooksCoordinator#evaluatePolicy(AggregateGeneration, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)}
 * and
 * {@link ClosingBooksLogicalAggregateRepository#closeAndOpenNextGenerationGuarded(LogicalAggregateId, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate, Object, ClosingBooksNextGenerationFactory)}.
 * Reload the aggregate from the generation that is open now and decide again, or drop the operation when the roll that
 * got there first already did what it wanted.
 * <p>
 * The guarded operations throw this only after the generation lock and the unit-of-work scopes they enter have
 * returned, so the refusal by itself does not mark the caller's unit of work rollback-only - except as the next
 * paragraph describes, when a guarded operation sees uncommitted changes on the aggregate: the reload and
 * the new decision can run, and commit, in the unit of work the caller loaded its aggregate in. This holds for the
 * guard's own refusal only; any other exception thrown inside a unit of work the caller joined still marks it
 * rollback-only.
 * <p>
 * The aggregate loaded from the moved generation stays registered with that unit of work: a change already recorded on
 * it (a closing event recorded before the guarded call) would be appended to the moved generation's stream when the
 * unit of work commits.
 * {@link ClosingBooksLogicalAggregateRepository#closeAndOpenNextGenerationGuarded(LogicalAggregateId, dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate, Object, ClosingBooksNextGenerationFactory)}
 * and the guarded
 * {@link ClosingBooksCoordinator#evaluatePolicy(AggregateGeneration, Object, ClosingBooksTriggerMode, ClosingBooksDecisionPolicy, ClosingBooksScanRollover)}
 * see that aggregate: when it is a {@link dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregate}
 * holding uncommitted changes, the refusal marks the unit of work rollback-only, with this exception as cause, so that
 * unit of work rolls back instead of committing those changes - retry in a new unit of work. After such a refusal
 * nothing more written in that unit of work commits, and with the JDBI unit of work no exception says so: its
 * {@code commit()} rolls back and returns normally.
 * <p>
 * <b>Deciding where to retry.</b> {@link #sameUnitOfWorkRetrySafe()} answers it: {@code true} only when the guarded
 * operation inspected the caller's aggregate - a {@code StatefulAggregate} - and found no uncommitted changes, so the
 * reload and the new decision can run, and commit, in the same unit of work. Otherwise let the unit of work roll back
 * and retry in a new one. Two flags say what the refusal saw and did: {@link #aggregateHeldUncommittedChanges()}
 * answers whether the guarded operation found the aggregate to be a {@code StatefulAggregate} holding uncommitted
 * changes, and {@link #unitOfWorkMarkedRollbackOnly()} whether the unit of work then took the rollback-only mark.
 * <ul>
 *     <li>{@link #sameUnitOfWorkRetrySafe()} is {@code true} (a {@code StatefulAggregate} inspected and found clean;
 *     both flags {@code false}): retry or drop the operation in the same unit of work;</li>
 *     <li>both flags {@code true}: nothing more written in that unit of work commits (the exception's message says
 *     so too): retry in a new unit of work;</li>
 *     <li>{@link #aggregateHeldUncommittedChanges()} {@code true} and {@link #unitOfWorkMarkedRollbackOnly()}
 *     {@code false}: no unit of work took the mark: one ignored it - e.g. a unit of work that joined a
 *     Spring-managed transaction it did not start (inside a {@code @Transactional} method) - or none was current.
 *     When a unit of work is current, the stale changes are still registered with its transaction: let this exception
 *     propagate out of the transaction so it rolls back, rather than catching it, and retry in a new unit of work (the
 *     exception's message says so too);</li>
 *     <li>the aggregate was not inspected (both flags {@code false}, {@link #sameUnitOfWorkRetrySafe()} {@code false}):
 *     the refusal cannot tell whether a stale change is registered with the unit of work - let it roll back (rethrow)
 *     and retry in a new one, unless the caller knows no change was recorded on the aggregate.</li>
 * </ul>
 * The aggregate is not inspected when it is not a {@code StatefulAggregate}: only a {@code StatefulAggregate}'s
 * {@code getUncommittedChanges()} is read, and a {@code FlexAggregate}'s or a decider's pending
 * {@code EventsToPersist}, which their repositories register with the unit of work, are not. Nor is it by
 * {@link ClosingBooksCoordinator#closeAndOpenNextGeneration(AggregateGeneration)}, which is not given the caller's
 * aggregate and never marks the unit of work rollback-only on a refusal.
 * <p>
 * The verdict is a derived {@code boolean} rather than a public enum of the inspection's outcome, so this stays a plain
 * {@link IllegalStateException} with no new public type to keep stable.
 */
public class ClosingBooksGenerationMovedException extends IllegalStateException {
    private final transient LogicalAggregateId<?>  logicalAggregateId;
    private final           String                 expectedStreamAggregateId;
    private final transient AggregateGeneration<?> openGeneration;
    private volatile        boolean                aggregateHeldUncommittedChanges;
    private volatile        boolean                unitOfWorkMarkedRollbackOnly;
    private volatile        boolean                aggregateInspectedClean;

    /**
     * @param logicalAggregateId        the logical aggregate whose generation moved
     * @param expectedStreamAggregateId the stream id of the generation the caller loaded its aggregate from
     * @param openGeneration            the generation that is open now, or {@code null} when none is open
     */
    public ClosingBooksGenerationMovedException(LogicalAggregateId<?> logicalAggregateId,
                                                String expectedStreamAggregateId,
                                                AggregateGeneration<?> openGeneration) {
        super(message(requireNonNull(logicalAggregateId, "No logicalAggregateId provided"),
                      requireNonNull(expectedStreamAggregateId, "No expectedStreamAggregateId provided"),
                      openGeneration));
        this.logicalAggregateId = logicalAggregateId;
        this.expectedStreamAggregateId = expectedStreamAggregateId;
        this.openGeneration = openGeneration;
    }

    private static String message(LogicalAggregateId<?> logicalAggregateId,
                                  String expectedStreamAggregateId,
                                  AggregateGeneration<?> openGeneration) {
        return "The generation with stream id '" + expectedStreamAggregateId + "' of logicalAggregateId '" + logicalAggregateId +
                "' is no longer the open generation: " +
                (openGeneration == null
                 ? "no generation is open"
                 : "generation " + openGeneration.generation() + " with stream id '" + openGeneration.streamAggregateId() + "' is open") +
                ". Nothing was closed or opened";
    }

    /**
     * The refusal's message. When {@link #unitOfWorkMarkedRollbackOnly()} is {@code true} it also says that nothing more
     * written in that unit of work will commit; when {@link #aggregateHeldUncommittedChanges()} is {@code true} but no
     * unit of work took the mark (none was current, or it ignored the mark), it says the exception must propagate and the
     * operation be retried in a new unit of work.
     */
    @Override
    public String getMessage() {
        var message = super.getMessage();
        if (unitOfWorkMarkedRollbackOnly) {
            return message + "; the unit of work was marked rollback-only, as the aggregate held uncommitted changes: nothing more written in it will commit - retry in a new unit of work";
        }
        if (aggregateHeldUncommittedChanges) {
            return message + "; the aggregate held uncommitted changes, but no unit of work took the rollback-only mark (none was current, or it ignored the mark): " +
                    "let this exception propagate and retry in a new unit of work";
        }
        return message;
    }

    /**
     * Whether the guarded operation found the caller's aggregate to be a {@code StatefulAggregate} holding uncommitted
     * changes - changes its repository would append to the moved generation's stream when the unit of work commits.
     * Read it together with {@link #unitOfWorkMarkedRollbackOnly()}: when this is {@code true} and that is
     * {@code false}, no unit of work took the mark: one ignored it (e.g. it joined a Spring-managed transaction it did not
     * start), or none was current. When a unit of work is current, the stale changes are still registered with it. In
     * either case let this exception propagate and retry in a new unit of work.
     * <p>
     * {@code false} does not by itself mean a retry in the same unit of work is safe - it is also {@code false} when the
     * aggregate was not inspected. Use {@link #sameUnitOfWorkRetrySafe()} for that.
     *
     * @return {@code true} when the guarded operation saw uncommitted changes on a {@code StatefulAggregate}
     */
    public boolean aggregateHeldUncommittedChanges() {
        return aggregateHeldUncommittedChanges;
    }

    /**
     * Whether this refusal left the caller's unit of work marked rollback-only: the guarded operation saw uncommitted
     * changes on the caller's aggregate, marked the unit of work it ran in rollback-only with this exception as cause,
     * and that unit of work then reported {@code UnitOfWorkStatus.MarkedForRollbackOnly}. When {@code true}, nothing
     * more written in that unit of work commits - with the JDBI unit of work its {@code commit()} rolls back without an
     * exception - so retry in a new unit of work. A unit of work that joined a Spring-managed transaction it did not
     * start (e.g. inside a {@code @Transactional} method) ignores the mark, so this answers {@code false} there.
     * <p>
     * {@code false} does not mean a retry in the same unit of work is safe: use {@link #sameUnitOfWorkRetrySafe()} for
     * that (see the class documentation).
     *
     * @return {@code true} when the unit of work the refusal was raised in is marked rollback-only
     */
    public boolean unitOfWorkMarkedRollbackOnly() {
        return unitOfWorkMarkedRollbackOnly;
    }

    /**
     * Whether the reload and the new decision can run, and commit, in the unit of work the caller loaded its aggregate
     * in. {@code true} only when the guarded operation inspected the caller's aggregate - a {@code StatefulAggregate} -
     * and found no uncommitted changes, so nothing stale is registered with that unit of work and the refusal did not
     * mark it rollback-only. {@code false} when the aggregate held uncommitted changes, and when it was not inspected:
     * it is not a {@code StatefulAggregate} (a {@code FlexAggregate}'s or a decider's pending {@code EventsToPersist}),
     * or the refusal came from {@link ClosingBooksCoordinator#closeAndOpenNextGeneration(AggregateGeneration)}, which is
     * not given the aggregate. When {@code false}, let the unit of work roll back and retry in a new one (see the class
     * documentation for the cases).
     *
     * @return {@code true} when a retry in the same unit of work is safe
     */
    public boolean sameUnitOfWorkRetrySafe() {
        return aggregateInspectedClean && !aggregateHeldUncommittedChanges;
    }

    /**
     * Set by the guarded operation when it found the caller's {@code StatefulAggregate} holding uncommitted changes,
     * whether or not a unit of work then took the rollback-only mark.
     */
    void recordAggregateHeldUncommittedChanges() {
        this.aggregateHeldUncommittedChanges = true;
    }

    /**
     * Set by the guarded operation when it inspected the caller's {@code StatefulAggregate} and found no uncommitted
     * changes.
     */
    void recordAggregateInspectedClean() {
        this.aggregateInspectedClean = true;
    }

    /**
     * Set by the guarded operation once it marked the unit of work rollback-only and that unit of work reports
     * {@code UnitOfWorkStatus.MarkedForRollbackOnly}.
     */
    void recordUnitOfWorkMarkedRollbackOnly() {
        this.unitOfWorkMarkedRollbackOnly = true;
    }

    /**
     * @return the logical aggregate whose generation moved
     */
    public LogicalAggregateId<?> logicalAggregateId() {
        return logicalAggregateId;
    }

    /**
     * @return the stream id of the generation the caller loaded its aggregate from
     */
    public String expectedStreamAggregateId() {
        return expectedStreamAggregateId;
    }

    /**
     * @return the generation that was open when the guard ran, or empty when none was open
     */
    public Optional<AggregateGeneration<?>> openGeneration() {
        return Optional.ofNullable(openGeneration);
    }
}
