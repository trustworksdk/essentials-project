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
 * Deliberately broken variants of the tail reader and the subscription, after the state-machine model's mutants
 * ({@code SubscriptionStateMachineModelTest}). <b>Test-only</b>: settable only through the
 * package-private {@link TestSeams}, so the invariant ITs can prove they catch each one. Normal use never sets any.
 */
enum WatermarkMutant {
    /** The probe reads the running set in a statement before the tail read, instead of after its snapshot (I11; see
     * docs/design/event-log.md §11) */
    RUNNING_SET_BEFORE_TAIL_READ,
    /** The reader runs in one explicit transaction, so every probe reuses the backend-status snapshot of its first (P0.2) */
    CACHED_STAT_ACTIVITY,
    /** An empty combined probe is taken to mean nobody is running */
    EMPTY_TAIL_WITHOUT_RUNNING_SET,
    /**
     * The watermark ignores holes whose candidate retired but whose hole read has not been published (F2). The loop
     * also offers that watermark to the attached inboxes before the hole read's rows
     */
    WATERMARK_BEFORE_HOLE_READ,
    /** A restarted reader takes everything at or below the head as final, with no initial candidate (F4) */
    RESTARTED_READER_AT_HEAD,
    /** While the restart's watermark is unknown it is taken to be the catch-up position (F4) */
    CATCH_UP_ABOVE_WATERMARK,
    /** The resume point is the highest handled order + 1 instead of the handled prefix of the received watermarks */
    RESUME_AT_HIGHEST_HANDLED,
    /** The resume point is the reader's cursor + 1 instead of the handled prefix of the received watermarks */
    RESUME_AT_CURSOR,
    /** The tail statement does not re-read outstanding holes; late commits arrive only from the hole read (F1) */
    NO_HOLE_REREAD,

    // ---- attach, bridge and detach ----

    /** A row already handled is handled again: no handled-set check where the bridge and the inbox overlap */
    NO_DEDUPE,
    /** The subscription attaches and drains without bridging {@code (P, C]} */
    ATTACH_WITHOUT_BRIDGE,
    /** Bridge pages do not re-read the attach holes below their lower bound (I2) */
    BRIDGE_WITHOUT_HOLE_REREAD,
    /** A detached subscription resumes at its received watermark, ignoring the unhandled rows of its batch (F5) */
    DETACH_RESUMES_AT_WATERMARK,
    /** The resume point is computed from the reader's live watermark instead of the received one (I3) */
    RESUME_FROM_READER_WATERMARK,
    /** A reader restart leaves the attached subscriptions attached (F4) */
    KEEP_ATTACHED_ON_READER_RESTART,
    /** The loop waits for room in a full inbox instead of detaching it (I9). On the type's first subscription */
    BACK_PRESSURE,
    /** The subscription bridges to the reader's published cursor first and attaches afterwards */
    BRIDGE_BEFORE_ATTACH,
    /** The subscription takes the batches already in its inbox before each bridge page (I2) */
    DRAIN_BEFORE_BRIDGE,
    /**
     * The loop accepts an attach while the type's watermark is unknown, replying with its cursor as the watermark, and
     * the subscription asks for one without waiting (F4). On the type's first subscription. The correct engine also
     * attaches under an unknown watermark, but only a {@code FRESH} subscription, only after
     * one backstop interval, with the watermark still unknown and its gaps handed off; the mutant is an ordinary attach
     * for either mode, at once, with nothing handed off
     */
    ATTACH_WITH_UNKNOWN_WATERMARK,

    // ---- page transactions and CONTIGUOUS release ----

    /** The progress update runs in its own transaction after the page committed (I12) */
    PROGRESS_AFTER_COMMIT,
    /** {@code CONTIGUOUS} releases held rows at the reader's live watermark instead of the received one (I3) */
    RELEASE_AT_READER_WATERMARK,
    /** {@code CONTIGUOUS} hands on every received row at once, as {@code FRESH} does */
    NO_HOLD_BACK,
    /** {@code CONTIGUOUS} attaches without the deferred bridge: rows in {@code (R, C]} never seen in a batch are
     * skipped */
    CONTIGUOUS_SKIPS_DEFERRED_BRIDGE,
    /** A detach drops the rows of a page that rolled back, as if it had committed */
    DETACH_POSITION_PAST_OPEN_PAGE,
    /** A detach catches up from the applied prefix as it was before the last page, without dropping what that page
     * applied */
    REREAD_WITHOUT_APPLIED_DROP,
    /** A {@code CONTIGUOUS} inbox stops counting an event when its batch is taken, so held-back rows are unbounded */
    HELD_NOT_COUNTED,
    /** The in-transaction progress update has no {@code resume_from = expected} guard */
    PROGRESS_WITHOUT_EXPECTED_RESUME,
    /** A {@code FRESH} bridge starts above the highest handled order without re-reading the handled set's gaps */
    BRIDGE_FROM_HIGHEST_WITHOUT_GAPS,

    // ---- failures and parking ----

    /** A failed page is retried whole, never shrunk; when its retries are used up its first event is parked */
    NO_SHRINK,
    /**
     * The pre-0.61.0 classification: an I/O-classified exception the handler threw (SQLState {@code 08*},
     * {@link java.sql.SQLTransientConnectionException}, also wrapped) counts as the engine's I/O failure, retried forever
     * and never parked, though the page connection is fine
     */
    HANDLER_IO_RETRIED_FOREVER,
    /** An in-transaction park row is inserted in its own transaction after the page that moves past it committed */
    PARK_OUTSIDE_PAGE_TRANSACTION,
    /** The page size doubles after every committed page, inside the suspect range too */
    GROW_INSIDE_SUSPECT_RANGE,
    /** Later events of a parked event's aggregate are handed on instead of parked behind it */
    NO_AGGREGATE_HOLD,
    /** The held aggregates are not loaded from {@code el_parked_events} at start */
    HELD_SET_NOT_RELOADED,

    // ---- filtered reads and catch-up permits ----

    /** A short filtered catch-up page advances to the reader's cursor instead of the read's ceiling (I13) */
    FILTERED_CATCH_UP_TO_CURSOR,
    /** The filter's type ids are resolved before the ceiling is read (I13) */
    TYPE_IDS_BEFORE_CEILING,
    /** A filtered {@code FRESH} bridge page marks the attach holes handled with its covered range (I13) */
    FILTERED_BRIDGE_MARKS_HOLES,
    /**
     * A filtered {@code FRESH} bridge under an unknown watermark marks its covered range handled, so a value held below
     * the head is no gap and never handed off (the tenant filter's bridge)
     */
    FILTERED_BRIDGE_MARKS_RANGE_UNDER_UNKNOWN_WATERMARK,
    /** A filtered inbox counts every row, matching or not, and stores zero-match batches whole */
    INBOX_COUNTS_UNMATCHED,
    /** A short filtered page advances only to its last row, not to the ceiling: it crawls */
    FILTERED_ADVANCE_TO_LAST_ROW,
    /** Catch-up and bridge reads take no permit (I15) */
    NO_CATCH_UP_PERMIT,
    /** Each {@code EventLog} has its own permits instead of one set per database (I15). On the event log's settings
     * seam */
    PERMIT_PER_EVENT_LOG,
    /** The permit is held until the rows read are applied, not released after the statement (I15) */
    PERMIT_HELD_THROUGH_PAGE,

    // ---- attach under an unknown watermark ----

    /**
     * A {@code FRESH} subscription attached under an unknown watermark goes straight to attached after its bridge: it
     * neither hands its gaps to the reader nor re-reads them once more (I1)
     */
    GAPS_NOT_HANDED_OFF,
    /**
     * The loop takes handed-off gaps as resolved at once: it neither holds nor re-reads them (I1). On the type's
     * first subscription
     */
    HANDED_GAPS_RESOLVED_AT_ONCE,
    /**
     * After the loop's hand-off reply the subscription takes batches from its inbox without re-reading the gaps once
     * more: a predecessor that committed into a gap before the hand-off reached the loop is delivered after a successor
     * already in the inbox, or never if the initial candidate retired (I2)
     */
    NO_REREAD_AFTER_HAND_OFF,

    // ---- reader loop and probe clock; loop mutants act if any active type's first subscription sets them ----

    /**
     * The loop parks without a deadline while no candidate is outstanding, trusting wake-ups: with every wake lost, a
     * committed event waits forever (I7)
     */
    PARK_UNTIL_WOKEN,
    /** A flush of several types reads the running set first, before every type's tail snapshot (I11) */
    RUNNING_SET_FIRST_IN_FLUSH,
    /**
     * A flush of several types reads a running set after each type's statement: every one after the first returns the
     * first one's backend status, cached for the flush's implicit transaction (I11, P0.2)
     */
    RUNNING_SET_PER_TYPE_IN_FLUSH,
    /** A woken read that finds no row takes the type's cursor as final: holes and candidates at or below it go */
    WAKE_ADVANCES_WATERMARK,
    /** The loop clears a type's wake flag after its read instead of before it: a wake during the read is lost */
    WAKE_CLEARED_AFTER_READ,
    /** Every woken read that wants a running set reads one: no probe clock after a wake */
    PROBE_ON_EVERY_WAKE,
    /**
     * A flush of several types records its whole wall time as each type's probe cost: under load the cost rule turns a
     * few milliseconds of tail reads into seconds before the next running set, and the watermark stalls behind a
     * candidate that could have retired
     */
    FLUSH_COUNTED_AS_PROBE_COST,
    /**
     * The probe wait is not capped at {@link EventLogSettings#holeProbeWaitMax()} while a hole waits: under load the cost
     * rule's back-off holds a rolled-back append's hole, and with it the watermark and every {@code CONTIGUOUS}
     * subscriber, for seconds
     */
    HOLE_PROBE_WAIT_UNCAPPED,

    // ---- wake-ups; on the event log's wake seams ({@code EventLog.wakeSeams}) ----

    /**
     * Each listener restart pushes every active type's next read a full backstop interval out instead of waking it:
     * restarts more frequent than the backstop starve the reader (I7)
     */
    BACKSTOP_REARMED_ON_LISTENER_RESTART,
    /** A reconnecting listener does not re-run {@code LISTEN}: after its first restart it hears nothing */
    RECONNECT_WITHOUT_LISTEN,
    /** A restarted listener does not wake the active types: a commit whose notification it missed waits a backstop */
    NO_WAKE_ON_LISTENER_RESTART,
    /** The listener drains with pgjdbc's stock {@code getNotifications(timeout)}: dense traffic holds every wake inside
     * the driver (P0.17) */
    STOCK_DRAIN,
    /** The listener acts on its own event log's notifications too: every local commit wakes the reader twice */
    NO_OWN_INSTANCE_FILTER,

    // ---- wake coalescing ----

    /**
     * A wake that arrives inside {@link EventLogSettings#wokenReadMin()} of the type's last read is dropped instead of
     * deferring a read to the interval's end: the commit waits for the backstop. Loop mutant, like the reader loop's
     */
    WOKEN_READ_DROPPED_INSIDE_INTERVAL,
    /**
     * The notifier drops the aggregate types that become pending inside {@link EventLogSettings#notifySendMin()} of its
     * last send instead of unioning them into the next one: other event logs wait for their backstop. On the event log's
     * wake seams
     */
    NOTIFY_DROPPED_INSIDE_INTERVAL,

    // ---- leases and fencing; subscription mutants on the subscription's seams, keeper mutants on EventLog.leaseSeams
    // ----

    /** Every save, page update and park insert binds a null fence: a stale owner's write lands (I5) */
    UNFENCED_SAVE,
    /**
     * The in-transaction page checks the fence with a separate, unlocked read before an unfenced update: the check passes
     * before the new owner's claim and the update lands after its commit (I5)
     */
    FENCE_READ_BEFORE_WRITE,
    /** The periodic park insert is an unguarded autocommit insert, with neither fence nor reset epoch (I5, I6) */
    UNGUARDED_PERIODIC_PARK,
    /** Keeper: the claim ignores a live lease and steals it (I5) */
    CLAIM_IGNORES_LIVE_LEASE,
    /** Every fenced write asserts the fence alone, not the owner: a duplicated fence after a failover lands */
    FENCE_WITHOUT_OWNER,
    /** Every save, page update and park insert ignores the reset epoch: a reset is undone (I6) */
    SAVE_WITHOUT_EPOCH,
    /** The release writes its resume point under any epoch: a reset racing a stop is undone (I6) */
    RELEASE_WRITES_ANY_EPOCH,
    /** No local deadline: an owner that cannot renew keeps delivering (I16) */
    NO_LOCAL_DEADLINE,
    /** Keeper: a renewal is confirmed at the time its reply arrived instead of before it was sent (I16) */
    DEADLINE_FROM_RENEWAL_END,
    /** Keeper: the renewal is keyed by subscription only, not by fence and owner: a lost lease is extended (I16) */
    RENEW_WITHOUT_FENCE,
    /** Keeper: one renewal statement per owned lease instead of one for all */
    RENEW_PER_LEASE,

    // ---- admin requests on parked events; subscription mutants, except where it says the event log's admin seams ----

    /** The parked check never runs: a request stored by an operator on another node is never served */
    PARKED_CHECK_SKIPPED,
    /** The parked check runs on every interval even while the subscription holds no aggregate (cost 0 without one) */
    PARKED_CHECK_ALWAYS,
    /**
     * The serve transaction takes no epoch-guarded lock on the progress row before the parked rows, and its progress update
     * ignores the epoch: a reset racing it deadlocks with it instead of being ordered after it (I6)
     */
    SERVE_WITHOUT_EPOCH,
    /** The serve transaction's progress lock and update bind no fence: an owner that lost its lease serves a request
     * (I5) */
    SERVE_WITHOUT_FENCE,
    /**
     * The admin reset on the caller's {@code Connection} runs on its own transaction instead: a rebuild that rolls back
     * leaves the view as it was under a reset resume point. On the event log's admin seams
     */
    RESET_OUTSIDE_CALLER_TRANSACTION,

    // ---- migration guard and progress import; on the event log's admin seams ----

    /**
     * {@code registerAggregateType} skips the {@code el_migration} read: a node started while the type is still being
     * copied registers it, and its reader takes the copied rows appearing below its watermark as retired holes
     */
    GUARD_SKIPPED,
    /** The progress import replaces an existing row's resume point instead of leaving it and returning it */
    IMPORT_PROGRESS_OVERWRITES,

    // ---- reader; on a type's first subscription ----

    /**
     * The stuck candidate's lock check reads no locker: a candidate older than {@link TailReader#LOCK_CHECK_AFTER}
     * retires while the append that drew its hole still runs, and that append's late commit lands below the watermark
     */
    LOCK_CHECK_WITHOUT_LOCKERS
}
