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
package dk.trustworks.essentials.components.eventlog;

/**
 * Deliberately broken variants of the tail reader and the subscription, after the phase-0 model's mutants
 * ({@code SubscriptionStateMachineModelTest}, plan §21 P0.5). <b>Test-only</b>: settable only through the
 * package-private {@link TestSeams}, so the invariant ITs can prove they catch each one. Normal use never sets any.
 */
enum WatermarkMutant {
    /** The probe reads the running set in a statement before the tail read (§4.10 reversed) */
    RUNNING_SET_BEFORE_TAIL_READ,
    /** The reader runs in one explicit transaction, so every probe reuses the backend-status snapshot of its first (P0.2) */
    CACHED_STAT_ACTIVITY,
    /** An empty combined probe is taken to mean nobody is running (§4.10) */
    EMPTY_TAIL_WITHOUT_RUNNING_SET,
    /**
     * The watermark ignores holes whose candidate retired but whose hole read has not been published (F2). In phase 3
     * the loop also offers that watermark to the attached inboxes before the hole read's rows
     */
    WATERMARK_BEFORE_HOLE_READ,
    /** A restarted reader takes everything at or below the head as final, with no initial candidate (F4) */
    RESTARTED_READER_AT_HEAD,
    /** While the restart's watermark is unknown it is taken to be the catch-up position (§10.1, F4) */
    CATCH_UP_ABOVE_WATERMARK,
    /** The resume point is the highest handled order + 1 instead of §4.7's formula */
    RESUME_AT_HIGHEST_HANDLED,
    /** The resume point is the reader's cursor + 1 instead of §4.7's formula */
    RESUME_AT_CURSOR,
    /** The tail statement does not re-read outstanding holes; late commits arrive only from the hole read (F1) */
    NO_HOLE_REREAD,

    // ---- phase 3 (design §5); the ITs that must catch them are phase 3's unit B ----

    /** A row already handled is handled again: no handled-set check (§3.2, §3.4 overlap) */
    NO_DEDUPE,
    /** The subscription attaches and drains without bridging {@code (P, C]} (§3.4) */
    ATTACH_WITHOUT_BRIDGE,
    /** Bridge pages do not re-read the attach holes below their lower bound (§3.4, I2) */
    BRIDGE_WITHOUT_HOLE_REREAD,
    /** A detached subscription resumes at its received watermark, ignoring the unhandled rows of its batch (F5, §3.6) */
    DETACH_RESUMES_AT_WATERMARK,
    /** The resume point is computed from the reader's live watermark instead of the received one (§3.2, I3) */
    RESUME_FROM_READER_WATERMARK,
    /** A reader restart leaves the attached subscriptions attached (§2.4, F4) */
    KEEP_ATTACHED_ON_READER_RESTART,
    /** The loop waits for room in a full inbox instead of detaching it (§2.3, I9). On the type's first subscription */
    BACK_PRESSURE,
    /** The subscription bridges to the reader's published cursor first and attaches afterwards (§3.4) */
    BRIDGE_BEFORE_ATTACH,
    /** The subscription takes the batches already in its inbox before each bridge page (§3.4, I2) */
    DRAIN_BEFORE_BRIDGE,
    /**
     * The loop accepts an attach while the type's watermark is unknown, replying with its cursor as the watermark, and
     * the subscription asks for one without waiting (§3.4 step 2, F4). On the type's first subscription. Since phase 5
     * §6.3 the correct engine also attaches under an unknown watermark, but only a {@code FRESH} subscription, only after
     * one backstop interval, with the watermark still unknown and its gaps handed off; the mutant is an ordinary attach
     * for either mode, at once, with nothing handed off
     */
    ATTACH_WITH_UNKNOWN_WATERMARK,

    // ---- phase 4 unit A (design phase 4 §8) ----

    /** The progress update runs in its own transaction after the page committed (§3.3, I12) */
    PROGRESS_AFTER_COMMIT,
    /** {@code CONTIGUOUS} releases held rows at the reader's live watermark instead of the received one (§2.2, I3) */
    RELEASE_AT_READER_WATERMARK,
    /** {@code CONTIGUOUS} hands on every received row at once, as {@code FRESH} does (§2.2) */
    NO_HOLD_BACK,
    /** {@code CONTIGUOUS} attaches without the deferred bridge: rows in {@code (R, C]} never seen in a batch are skipped (§2.4) */
    CONTIGUOUS_SKIPS_DEFERRED_BRIDGE,
    /** A detach drops the rows of a page that rolled back, as if it had committed (§2.5, §7.1) */
    DETACH_POSITION_PAST_OPEN_PAGE,
    /** A detach catches up from the applied prefix as it was before the last page, without dropping what that page applied (§7.1) */
    REREAD_WITHOUT_APPLIED_DROP,
    /** A {@code CONTIGUOUS} inbox stops counting an event when its batch is taken, so held-back rows are unbounded (§2.3) */
    HELD_NOT_COUNTED,
    /** The in-transaction progress update has no {@code resume_from = expected} guard (§3.3) */
    PROGRESS_WITHOUT_EXPECTED_RESUME,
    /** A {@code FRESH} bridge starts above the highest handled order without re-reading the handled set's gaps (§5.3) */
    BRIDGE_FROM_HIGHEST_WITHOUT_GAPS,

    // ---- phase 4 unit B (design phase 4 §4, §8) ----

    /** A failed page is retried whole, never shrunk; when its retries are used up its first event is parked (§4.2) */
    NO_SHRINK,
    /** An in-transaction park row is inserted in its own transaction after the page that moves past it committed (§4.4) */
    PARK_OUTSIDE_PAGE_TRANSACTION,
    /** The page size doubles after every committed page, inside the suspect range too (§4.2) */
    GROW_INSIDE_SUSPECT_RANGE,
    /** Later events of a parked event's aggregate are handed on instead of parked behind it (§4.5) */
    NO_AGGREGATE_HOLD,
    /** The held aggregates are not loaded from {@code el_parked_events} at start (§4.5) */
    HELD_SET_NOT_RELOADED,

    // ---- phase 5 unit A (design phase 5 §7) ----

    /** A short filtered catch-up page advances to the reader's cursor instead of the read's ceiling (§2.2, I13) */
    FILTERED_CATCH_UP_TO_CURSOR,
    /** The filter's type ids are resolved before the ceiling is read (§2.2, I13) */
    TYPE_IDS_BEFORE_CEILING,
    /** A filtered {@code FRESH} bridge page marks the attach holes handled with its covered range (§2.3, I13) */
    FILTERED_BRIDGE_MARKS_HOLES,
    /** A filtered inbox counts every row, matching or not, and stores zero-match batches whole (§2.4) */
    INBOX_COUNTS_UNMATCHED,
    /** A short filtered page advances only to its last row, not to the ceiling: it crawls (§2.2, §7.3) */
    FILTERED_ADVANCE_TO_LAST_ROW,
    /** Catch-up and bridge reads take no permit (§4, I15) */
    NO_CATCH_UP_PERMIT,
    /** Each {@code EventLog} has its own permits instead of one set per database (§4.2, I15). On the event log's settings seam */
    PERMIT_PER_EVENT_LOG,
    /** The permit is held until the rows read are applied, not released after the statement (§4.2, I15) */
    PERMIT_HELD_THROUGH_PAGE,

    // ---- phase 5 unit B (design phase 5 §6.3, §7.1) ----

    /**
     * A {@code FRESH} subscription attached under an unknown watermark goes straight to attached after its bridge: it
     * neither hands its gaps to the reader nor re-reads them once more (§6.3, I1)
     */
    GAPS_NOT_HANDED_OFF,
    /**
     * The loop takes handed-off gaps as resolved at once: it neither holds nor re-reads them (§6.3, I1). On the type's
     * first subscription
     */
    HANDED_GAPS_RESOLVED_AT_ONCE,
    /**
     * After the loop's hand-off reply the subscription takes batches from its inbox without re-reading the gaps once
     * more: a predecessor that committed into a gap before the hand-off reached the loop is delivered after a successor
     * already in the inbox, or never if the initial candidate retired (§6.3, I2)
     */
    NO_REREAD_AFTER_HAND_OFF,

    // ---- phase 6 unit A (design phase 6 §6.1, §6.2, §6.4); loop mutants act if any active type's first subscription sets them ----

    /**
     * The loop parks without a deadline while no candidate is outstanding, trusting wake-ups: with every wake lost, a
     * committed event waits forever (§6.1, I7)
     */
    PARK_UNTIL_WOKEN,
    /** A flush of several types reads the running set first, before every type's tail snapshot (§4.2, I11) */
    RUNNING_SET_FIRST_IN_FLUSH,
    /**
     * A flush of several types reads a running set after each type's statement: every one after the first returns the
     * first one's backend status, cached for the flush's implicit transaction (§4.2, I11, P0.2)
     */
    RUNNING_SET_PER_TYPE_IN_FLUSH,
    /** A woken read that finds no row takes the type's cursor as final: holes and candidates at or below it go (§4.3) */
    WAKE_ADVANCES_WATERMARK,
    /** The loop clears a type's wake flag after its read instead of before it: a wake during the read is lost (§4.1) */
    WAKE_CLEARED_AFTER_READ,
    /** Every woken read that wants a running set reads one: no probe clock after a wake (§5) */
    PROBE_ON_EVERY_WAKE,

    // ---- phase 6 unit B (design phase 6 §6.1, §6.3); on the event log's wake seams ({@code EventLog.wakeSeams}) ----

    /**
     * Each listener restart pushes every active type's next read a full backstop interval out instead of waking it:
     * restarts more frequent than the backstop starve the reader (§6.1, I7)
     */
    BACKSTOP_REARMED_ON_LISTENER_RESTART,
    /** A reconnecting listener does not re-run {@code LISTEN}: after its first restart it hears nothing (§3.3) */
    RECONNECT_WITHOUT_LISTEN,
    /** A restarted listener does not wake the active types: a commit whose notification it missed waits a backstop (§3.3) */
    NO_WAKE_ON_LISTENER_RESTART,
    /** The listener drains with pgjdbc's stock {@code getNotifications(timeout)}: dense traffic holds every wake inside the driver (§3.3, P0.17) */
    STOCK_DRAIN,
    /** The listener acts on its own event log's notifications too: every local commit wakes the reader twice (§3.1, §3.3) */
    NO_OWN_INSTANCE_FILTER,

    // ---- phase 6 unit D (design phase 6 §10.8, coalescing) ----

    /**
     * A wake that arrives inside {@link EventLogSettings#wokenReadMin()} of the type's last read is dropped instead of
     * deferring a read to the interval's end: the commit waits for the backstop. Loop mutant, as phase 6 unit A's
     */
    WOKEN_READ_DROPPED_INSIDE_INTERVAL,
    /**
     * The notifier drops the aggregate types that become pending inside {@link EventLogSettings#notifySendMin()} of its
     * last send instead of unioning them into the next one: other event logs wait for their backstop. On the event log's
     * wake seams
     */
    NOTIFY_DROPPED_INSIDE_INTERVAL
}
