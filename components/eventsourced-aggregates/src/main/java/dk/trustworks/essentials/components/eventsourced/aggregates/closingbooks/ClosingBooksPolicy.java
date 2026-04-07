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
 * Functional interface representing a policy used to determine whether a set of books
 * or aggregate data should be closed based on a provided context.
 *
 * @param <ID>        the type of the identifier for the logical aggregate
 * @param <AGGREGATE> the type of the aggregate being evaluated
 */
@FunctionalInterface
public interface ClosingBooksPolicy<ID, AGGREGATE> {
    /**
     * Determines whether the books or aggregate data in the given context should be closed.
     *
     * @param context the context providing details about the aggregate, including its type,
     *                identifier, current generation, and actual aggregate instance
     * @return {@code true} if the books or aggregate should be closed, {@code false} otherwise
     */
    boolean shouldCloseBooks(ClosingBooksContext<ID, AGGREGATE> context);
}
