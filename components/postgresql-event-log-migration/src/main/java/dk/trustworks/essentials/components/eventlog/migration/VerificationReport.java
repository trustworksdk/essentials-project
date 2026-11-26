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
import java.util.*;

/**
 * The outcome of a verification: per aggregate type, the windows of {@code global_order} hashed on
 * both sides over every column, the rows a differing window was bisected down to, and the structural checks.
 *
 * @param mode  what was verified
 * @param types per aggregate type
 * @param took  how long the verification took
 */
public record VerificationReport(Mode mode, List<TypeVerification> types, Duration took) {
    public VerificationReport {
        types = List.copyOf(types);
    }

    public enum Mode {
        /** The copied ranges, while the old engine runs; ranges copied by an epoch are final */
        ONLINE,
        /** The range the cut-over's final pass copied, under the {@code SHARE} locks, and the identity sequences */
        CUTOVER
    }

    /** @return whether every type verified equal */
    public boolean equal() {
        return types.stream().allMatch(TypeVerification::equal);
    }

    public Optional<TypeVerification> type(String aggregateType) {
        return types.stream().filter(t -> t.aggregateType().equals(aggregateType)).findFirst();
    }

    /** @return a human-readable account, one block per type */
    public String describe() {
        var sb = new StringBuilder();
        for (var t : types) {
            sb.append("  ").append(t.aggregateType()).append(": global_order (").append(t.from()).append(", ").append(t.to()).append("], ").append(t.rows())
              .append(" rows in ").append(t.windows().size()).append(" windows: ").append(t.equal() ? "EQUAL" : "DIFFERS").append('\n');
            for (var w : t.differingWindows()) {
                sb.append("    window (").append(w.from()).append(", ").append(w.to()).append("]: ").append(w.oldRows()).append(" old rows, ").append(w.newRows())
                  .append(" event log rows, md5 ").append(w.oldMd5()).append(" / ").append(w.newMd5()).append('\n');
            }
            for (var r : t.differingRows()) {
                sb.append("    global_order ").append(r.globalOrder()).append(": ").append(r.kind()).append('\n');
            }
            for (var f : t.structuralFailures()) {
                sb.append("    ").append(f).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * @param aggregateType      the aggregate type
     * @param from               the verified range's exclusive lower bound
     * @param to                 its inclusive upper bound
     * @param rows               the old table's rows in the range
     * @param windows            every window of the range, with both sides' row counts and hashes
     * @param differingRows      the rows the differing windows were bisected down to, in {@code global_order} order;
     *                           at most {@value Verifier#MAX_DIFFERING_ROWS}
     * @param structuralFailures the failed structural checks: heads, and under the cut-over the identity sequence
     */
    public record TypeVerification(String aggregateType, long from, long to, long rows, List<Window> windows, List<RowDifference> differingRows,
                                   List<String> structuralFailures) {
        public TypeVerification {
            windows = List.copyOf(windows);
            differingRows = List.copyOf(differingRows);
            structuralFailures = List.copyOf(structuralFailures);
        }

        public boolean equal() {
            return windows.stream().allMatch(Window::equal) && structuralFailures.isEmpty();
        }

        public List<Window> differingWindows() {
            return windows.stream().filter(w -> !w.equal()).toList();
        }
    }

    /** One window {@code (from, to]} of {@code global_order}: each side's row count and hash */
    public record Window(long from, long to, long oldRows, long newRows, String oldMd5, String newMd5) {
        public boolean equal() {
            return oldRows == newRows && oldMd5.equals(newMd5);
        }
    }

    /** A row that differs between the old table and the event log's */
    public record RowDifference(long globalOrder, Kind kind) {
        public enum Kind {
            /** In the old table only: not copied */
            MISSING_IN_EVENT_LOG,
            /** In the event log's table only: written by someone other than the migration */
            ONLY_IN_EVENT_LOG,
            /** In both, but some column differs */
            DIFFERENT
        }
    }
}
