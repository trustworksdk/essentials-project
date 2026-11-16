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
     * the subscription asks for one without waiting (§3.4 step 2, F4). On the type's first subscription
     */
    ATTACH_WITH_UNKNOWN_WATERMARK
}
