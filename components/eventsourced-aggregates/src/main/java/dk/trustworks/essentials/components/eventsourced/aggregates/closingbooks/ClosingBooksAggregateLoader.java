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

/**
 * Functional interface for loading an aggregate of a specific type by its associated
 * stream aggregate identifier.
 *
 * This interface provides an abstraction for the retrieval of aggregates from some
 * underlying data source or storage mechanism using an identifier.
 *
 * @param <AGGREGATE> the type of the aggregate to be loaded
 */
@FunctionalInterface
public interface ClosingBooksAggregateLoader<AGGREGATE> {
    Optional<AGGREGATE> load(String streamAggregateId);
}
