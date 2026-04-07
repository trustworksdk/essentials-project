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
 * Enumeration representing the decision for handling the closing of books associated
 * with a logical aggregate's lifecycle. Each decision indicates how the process of
 * closing books should be carried out.
 *
 * <ul>
 *   <li>KEEP_OPEN: Indicates that the books should remain open without any changes.</li>
 *   <li>CLOSE_ONLY: Indicates that the current set of books should be closed.</li>
 *   <li>CLOSE_AND_OPEN_NEXT: Indicates that the current set of books should be closed
 *       and a new set of books should be opened for the next generation.</li>
 * </ul>
 */
public enum ClosingBooksDecision {
    /**
     * Represents the decision to keep the books of a logical aggregate open without
     * making any changes. This indicates that the current generation will remain
     * active and operational as is, without transitioning to a closed or next open
     * state.
     */
    KEEP_OPEN,
    /**
     * Represents the decision to close the current set of books associated with
     * a logical aggregate. This decision implies that the current generation
     * will be finalized and transitioned to a closed state without initiating
     * any subsequent generation.
     */
    CLOSE_ONLY,
    /**
     * Represents the decision to close the current set of books associated with a logical aggregate
     * and simultaneously open a new set of books for a subsequent generation. This decision implies
     * a transition where the existing generation is finalized, and a new generation starts immediately
     * to maintain continuity in operations.
     */
    CLOSE_AND_OPEN_NEXT
}
