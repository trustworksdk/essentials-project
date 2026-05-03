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
 * Enumerates the various modes that can trigger the closing of books within
 * the context of event-sourced aggregates. These modes represent distinct strategies
 * for determining when the transition to a closed state should occur for a set of aggregates.
 */
public enum ClosingBooksTriggerMode {
    /**
     * Indicates that the closing of books should be triggered upon access.
     * This mode is used in scenarios where the evaluation of whether books
     * should be closed is deferred until an interaction with the aggregate or
     * related data occurs. This strategy ensures that closing decisions are
     * made dynamically when the data is accessed.
     */
    ON_ACCESS,
    /**
     * Indicates that the closing of books should be triggered via an explicit command.
     * This mode is used in scenarios where the transition to the closed state is controlled
     * manually by initiating a specific operation or dispatching a command. It provides
     * fine-grained control over when the closing process should be executed.
     */
    EXPLICIT_COMMAND,
    /**
     * Indicates that the closing of books should be triggered based on a scheduled scan.
     * This mode involves periodic or pre-defined scanning mechanisms to evaluate whether
     * the books should transition to a closed state. It is useful in scenarios where
     * automated and time-driven processes determine closing decisions without direct
     * user interaction or specific access events.
     */
    SCHEDULED_SCAN
}
