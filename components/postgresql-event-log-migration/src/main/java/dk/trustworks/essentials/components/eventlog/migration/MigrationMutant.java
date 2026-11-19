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

package dk.trustworks.essentials.components.eventlog.migration;

/**
 * Deliberate defects, each switched on only through {@link MigrationSeams} by a test in this
 * package; every one has a {@code must_fail_*} test that shows its defect.
 */
enum MigrationMutant {
    /** The payload and metadata go through a {@code ::jsonb} round trip, which re-renders a {@code json} source's text
     * */
    PAYLOAD_VIA_JSONB,
    /** The batch's inserted row count is not checked against the range's {@code count(*)}: a type join drops rows
     * silently */
    TYPE_JOIN_DROPS_ROW,
    /** An epoch copies up to {@code M} without waiting for the transactions older than its start */
    EPOCH_WITHOUT_WAIT,
    /** The cut-over copies the delta without taking the {@code SHARE} locks on the old tables */
    CUTOVER_WITHOUT_LOCK,
    /** The checkpoint commits on its own, before the batch's insert */
    CHECKPOINT_OUTSIDE_BATCH,
    /** The identity sequence is set to the highest copied row, not to {@code greatest(old last_value, max)} */
    SEQUENCE_AT_MAX_ROW,
    /** The copy writes no head rows */
    HEADS_NOT_WRITTEN,
    /** The verification hashes leave out the payload and the metadata */
    VERIFY_WITHOUT_PAYLOAD,
    /**
     * The cut-over runs without its verification step. Not a defect a test looks for: it lets the tests whose
     * oracle is the engine's behaviour after a cut-over (heads, sequence, payload bytes) commit a divergent copy. It also skips
     * {@link ResumePointImport}'s progress-row checks, so I8's must-fail can show the loss those checks would refuse
     */
    CUTOVER_WITHOUT_VERIFY,
    /** The import writes the old resume point unchanged, {@code I = R}: a gap fill committed before the cut-over is
     * lost (I8's must-fail) */
    IMPORT_RESUME_UNCHANGED,
    /**
     * The import resumes from the lowest open transient gap, filled or not (a stricter rule). Not a correctness
     * mutant: it only replays more; the test asserts the replay length to show the tighter rule is in use
     */
    IMPORT_LOWEST_GAP_UNFILTERED
}
