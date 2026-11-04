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

/**
 * How a scheduled scan rolls an aggregate to its next generation (EF-1b): the application's own rollover, the same one
 * it performs on access.
 * <p>
 * Without it a scan that decides {@link ClosingBooksDecision#CLOSE_AND_OPEN_NEXT} only closes the generation row and
 * opens the next one with an empty stream: no closing event in the old generation, no state carried forward, no period
 * id. That is half a rollover for any aggregate that carries balances or a period. The rollover typically records the
 * aggregate's closing event and calls
 * {@link ClosingBooksLogicalAggregateRepository#closeAndOpenNextGeneration(LogicalAggregateId, Object, Object, ClosingBooksNextGenerationFactory)}
 * with its next-generation factory and the next period id as the hint - exactly what an on-access rollover does.
 * <p>
 * Called by {@link DefaultClosingBooksScheduledScanProcessor} inside the aggregate's generation lock and a unit of
 * work, after the policy decided {@code CLOSE_AND_OPEN_NEXT} for the generation in the context. If it throws, the unit
 * of work rolls back and the generation stays open; the scan defers it and tries again later.
 *
 * @param <ID>        the logical aggregate id type
 * @param <AGGREGATE> the aggregate type
 */
@FunctionalInterface
public interface ClosingBooksScanRollover<ID, AGGREGATE> {
    /**
     * Roll the aggregate in {@code context} to its next generation.
     *
     * @param context what the policy decided on: the logical id, the current (open) generation and its loaded aggregate
     */
    void rollover(ClosingBooksEvaluationContext<ID, AGGREGATE> context);
}
