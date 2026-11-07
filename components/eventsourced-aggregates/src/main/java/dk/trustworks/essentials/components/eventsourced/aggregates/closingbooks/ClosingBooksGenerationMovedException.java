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
 */
public class ClosingBooksGenerationMovedException extends IllegalStateException {
    private final transient LogicalAggregateId<?>  logicalAggregateId;
    private final           String                 expectedStreamAggregateId;
    private final transient AggregateGeneration<?> openGeneration;

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
