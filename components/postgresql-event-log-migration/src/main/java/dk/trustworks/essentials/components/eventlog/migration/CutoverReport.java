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

import java.time.Duration;
import java.util.List;

/**
 * The outcome of a committed cut-over (design phase 9 §3.5).
 *
 * @param types    per aggregate type, what the final pass copied and where its identity sequence now stands
 * @param lockWait how long acquiring the {@code SHARE} locks took
 * @param stop     how long the old tables were locked: from the lock request to the commit
 */
public record CutoverReport(List<TypeCutover> types, Duration lockWait, Duration stop) {
    public CutoverReport {
        types = List.copyOf(types);
    }

    /**
     * @param aggregateType the aggregate type
     * @param from          the copied range's exclusive lower bound (the last epoch's {@code copied_through})
     * @param to            its inclusive upper bound: the old table's {@code max(global_order)} under the lock; recorded as
     *                      {@code el_migration.copied_through}, which {@code verify --after-cutover} compares against
     * @param rows          the rows copied by the final pass
     * @param sequenceValue the value the event table's identity sequence was set to ({@code is_called}), or 0 if it was
     *                      left untouched because nothing was ever drawn or copied (§3.6)
     */
    public record TypeCutover(String aggregateType, long from, long to, long rows, long sequenceValue) {
    }
}
