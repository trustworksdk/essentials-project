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

import java.time.*;
import java.util.List;

/**
 * The outcome of one online epoch.
 *
 * @param types    per aggregate type, the range the epoch copied; empty if the wait timed out
 * @param waited   how long the epoch waited for the transactions older than its start
 * @param timedOut whether the wait timed out, in which case nothing was copied
 * @param blockers the transactions still running when the wait timed out (empty otherwise)
 * @param copyTime the time spent copying, throttle included
 */
public record EpochReport(List<TypeEpoch> types, Duration waited, boolean timedOut, List<Blocker> blockers, Duration copyTime) {
    public EpochReport {
        types = List.copyOf(types);
        blockers = List.copyOf(blockers);
    }

    /** @return the rows copied over all types */
    public long rows() {
        return types.stream().mapToLong(TypeEpoch::rows).sum();
    }

    /**
     * @param aggregateType the aggregate type
     * @param from          the exclusive lower bound, {@code copied_through} before the epoch
     * @param to            the inclusive upper bound, the old table's {@code max(global_order)} at the epoch's start
     * @param rows          the rows copied
     */
    public record TypeEpoch(String aggregateType, long from, long to, long rows) {
    }

    /**
     * A transaction that held up the epoch wait ({@code pg_stat_activity})
     */
    public record Blocker(int pid, String applicationName, OffsetDateTime transactionStart, String state) {
    }
}
