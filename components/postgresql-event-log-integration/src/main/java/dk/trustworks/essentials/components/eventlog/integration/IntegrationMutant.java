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

package dk.trustworks.essentials.components.eventlog.integration;

/**
 * <b>Test-only</b> broken variants of the processors and page sources (design phase 8 §9.2), which the ITs must be seen
 * to catch. Settable only through package-private seams ({@link ProcessorSeams}); never in normal use.
 */
enum IntegrationMutant {
    /** The processor catches a handler's exception and carries on: the event counts as handled (breaks I14) */
    PROCESSOR_SWALLOWS_FAILURE,
    /** A payload that cannot be deserialized is reported as an I/O failure: retried forever, never parked */
    DESERIALIZE_OUTSIDE_HANDLER_RETRY,
    /** The page source hands out its typed resources unwrapped: a handler's commit lands output without progress (I12) */
    HANDLER_COMMITS_EARLY,
    /** The adapter lets the engine's StreamAppendConflictException escape instead of OptimisticAppendToStreamException (§9.4) */
    CONFLICT_UNMAPPED,
    /** The adapter wakes the engine at append, before commit, instead of through the commit callback (§9.4) */
    PUBLISH_BEFORE_COMMIT,
    /** EventLogViewProcessor / EventLogReactor catch a {@code @MessageHandler}'s exception: the event counts as handled (phase 10 §3.5) */
    ANNOTATED_DISPATCH_SWALLOWS_FAILURE,
    /** EventLogInboxProcessor enqueues in a unit of work of its own, not the page's: a page that fails after the enqueue forwards its events again (phase 10 §3.5) */
    INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION
}
