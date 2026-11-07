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
 * {@code commit()} rolls back and returns normally. So check {@link #unitOfWorkMarkedRollbackOnly()} - or
 * {@code uow.status() == UnitOfWorkStatus.MarkedForRollbackOnly} - before retrying in the same unit of work; the
 * exception's message says so too when it is {@code true}.
 * A unit of work that joined a Spring-managed transaction it did not start (e.g. inside a {@code @Transactional}
 * method) ignores that mark, and {@link #unitOfWorkMarkedRollbackOnly()} then answers {@code false}: there, let this
 * exception propagate out of the transaction so it rolls back, rather than catching it.
 * {@link ClosingBooksCoordinator#closeAndOpenNextGeneration(AggregateGeneration)} is not given the caller's aggregate,
 * so it does not check it for changes and never marks the unit of work rollback-only on a refusal: with it, retry or
 * drop the operation in the same unit of work only when no change was recorded on the aggregate; otherwise let the
 * unit of work roll back - rethrow - and retry in a new one.
 */
public class ClosingBooksGenerationMovedException extends IllegalStateException {
    private final transient LogicalAggregateId<?>  logicalAggregateId;
    private final           String                 expectedStreamAggregateId;
    private final transient AggregateGeneration<?> openGeneration;
    private volatile        boolean                unitOfWorkMarkedRollbackOnly;

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
     * The refusal's message; when {@link #unitOfWorkMarkedRollbackOnly()} is {@code true} it also says that nothing more
     * written in that unit of work will commit.
     */
    @Override
    public String getMessage() {
        var message = super.getMessage();
        return unitOfWorkMarkedRollbackOnly
               ? message + "; the unit of work was marked rollback-only, as the aggregate held uncommitted changes: nothing more written in it will commit - retry in a new unit of work"
               : message;
    }

    /**
     * Whether this refusal left the caller's unit of work marked rollback-only: the guarded operation saw uncommitted
     * changes on the caller's aggregate, marked the unit of work it ran in rollback-only with this exception as cause,
     * and that unit of work then reported {@code UnitOfWorkStatus.MarkedForRollbackOnly}. When {@code true}, nothing
     * more written in that unit of work commits - with the JDBI unit of work its {@code commit()} rolls back without an
     * exception - so retry in a new unit of work. A unit of work that joined a Spring-managed transaction it did not
     * start (e.g. inside a {@code @Transactional} method) ignores the mark, so this answers {@code false} there.
     *
     * @return {@code true} when the unit of work the refusal was raised in is marked rollback-only
     */
    public boolean unitOfWorkMarkedRollbackOnly() {
        return unitOfWorkMarkedRollbackOnly;
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
