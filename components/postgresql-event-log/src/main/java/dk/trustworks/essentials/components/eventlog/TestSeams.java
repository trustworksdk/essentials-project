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

import java.util.*;

/**
 * <b>Test-only</b> seams of the tail reader and the phase-2 subscription: the phase-0 mutants the invariant ITs must
 * be seen to catch, hooks into the reader's single thread at the two points the invariants care about, and a read
 * source in place of the event table (a view that holds a scan open, for the hold-inside-scan proofs). Package-private;
 * {@link #NONE} in normal use.
 *
 * @param mutants        broken variants to switch on
 * @param hooks          callbacks on the reader's thread
 * @param readTable      a relation to read instead of the aggregate type's event table, or {@code null}
 */
record TestSeams(Set<WatermarkMutant> mutants, Hooks hooks, String readTable) {
    static final TestSeams NONE = new TestSeams(Set.of(), Hooks.NONE, null);

    TestSeams {
        mutants = mutants.isEmpty() ? Set.of() : EnumSet.copyOf(mutants);
    }

    boolean has(WatermarkMutant mutant) {
        return mutants.contains(mutant);
    }

    interface Hooks {
        Hooks NONE = new Hooks() {
        };

        /** Before every read, plain or combined */
        default void beforeRead(boolean combined) throws Exception {
        }

        /** {@link WatermarkMutant#RUNNING_SET_BEFORE_TAIL_READ} only: between the two statements */
        default void afterRunningSetRead() throws Exception {
        }

        /** After a candidate retired and before its hole read is sent; {@code holes} are the values to re-read */
        default void beforeHoleRead(Collection<Long> holes) throws Exception {
        }
    }
}
