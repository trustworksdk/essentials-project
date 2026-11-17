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
 * Deliberate defects (design phase 9 §9), each switched on only through {@link MigrationSeams} by a test in this
 * package; every one has a {@code must_fail_*} test that shows its defect.
 */
enum MigrationMutant {
    /** The payload and metadata go through a {@code ::jsonb} round trip, which re-renders a {@code json} source's text (§2.3) */
    PAYLOAD_VIA_JSONB,
    /** The batch's inserted row count is not checked against the range's {@code count(*)}: a type join drops rows silently (§3.4) */
    TYPE_JOIN_DROPS_ROW,
    /** An epoch copies up to {@code M} without waiting for the transactions older than its start (§3.3) */
    EPOCH_WITHOUT_WAIT,
    /** The cut-over copies the delta without taking the {@code SHARE} locks on the old tables (§3.5) */
    CUTOVER_WITHOUT_LOCK,
    /** The checkpoint commits on its own, before the batch's insert (§3.7) */
    CHECKPOINT_OUTSIDE_BATCH,
    /** The identity sequence is set to the highest copied row, not to {@code greatest(old last_value, max)} (§3.6) */
    SEQUENCE_AT_MAX_ROW,
    /** The copy writes no head rows (§3.6) */
    HEADS_NOT_WRITTEN
}
