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

import java.util.Set;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A watermark candidate: {@code highestRead} is the highest {@code global_order} the reader had read when
 * it read {@code runningXids}, the {@code backend_xid}s of every writer running after that read's snapshot. Any writer
 * that could still commit a value at or below {@code highestRead} is in that set, so once the set is disjoint from a
 * later running set, every value at or below {@code highestRead} is resolved.
 * <p>
 * Compared by set disjointness, never numerically: {@code backend_xid} is a wrapping 32-bit xid.
 *
 * @param highestRead the highest value read
 * @param runningXids the running set read after it, as text
 * @param initial     the restart candidate of F4: until it retires the reader's watermark is unknown
 * @param createdNanos {@link System#nanoTime()} at creation, for the age metric
 */
record Candidate(long highestRead, Set<String> runningXids, boolean initial, long createdNanos) {
    Candidate {
        runningXids = Set.copyOf(requireNonNull(runningXids, "No runningXids provided"));
    }

    /**
     * @return whether no writer of this candidate is in {@code running} any more
     */
    boolean retiresAgainst(Set<String> running) {
        for (var xid : runningXids) {
            if (running.contains(xid)) {
                return false;
            }
        }
        return true;
    }
}
