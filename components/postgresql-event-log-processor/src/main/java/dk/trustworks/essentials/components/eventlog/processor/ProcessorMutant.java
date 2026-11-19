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

package dk.trustworks.essentials.components.eventlog.processor;

/**
 * <b>Test-only</b> broken variants of the processors and page sources, which the ITs must be seen
 * to catch. Settable only through package-private seams ({@link ProcessorSeams}); never in normal use.
 */
enum ProcessorMutant {
    /** The processor catches a handler's exception and carries on: the event counts as handled (breaks I14) */
    PROCESSOR_SWALLOWS_FAILURE,
    /** The page source hands out its typed resources unwrapped: a handler's commit lands output without progress (I12;
     * see docs/design/event-log.md §11) */
    HANDLER_COMMITS_EARLY,
    /** The adapter lets the engine's StreamAppendConflictException escape instead of OptimisticAppendToStreamException
     * */
    CONFLICT_UNMAPPED,
    /** The adapter wakes the engine at append, before commit, instead of through the commit callback */
    PUBLISH_BEFORE_COMMIT,
    /** EventLogViewProcessor / EventLogReactor catch a {@code @MessageHandler}'s exception: the event counts as handled
     * */
    ANNOTATED_DISPATCH_SWALLOWS_FAILURE,
    /** EventLogInboxProcessor enqueues in a unit of work of its own, not the page's: a page that fails after the
     * enqueue forwards its events again */
    INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION,
    /** EventLogViewProcessor's rebuild commits onRebuild's clear before resetting the subscriptions: a rebuild that
     * resets nothing still empties the view */
    REBUILD_CLEARS_OUTSIDE_RESET_TRANSACTION
}
