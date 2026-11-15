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
 * <b>Test-only</b> seams of the tail reader and the subscription: the mutants the invariant ITs must be seen to catch,
 * hooks into the reader loop's thread and the delivery thread at the points the invariants care about, and a read
 * source in place of the event table (a view that holds a scan open, for the hold-inside-scan proofs). Package-private;
 * {@link #NONE} in normal use.
 * <p>
 * The tail reader of an aggregate type is shared (phase 3 §2.1): its mutants, reader hooks and read source are those of
 * the subscription that made the type active (the first one to start); the subscription mutants and delivery-thread
 * hooks are each subscription's own.
 *
 * @param mutants        broken variants to switch on
 * @param hooks          callbacks on the reader loop's and the delivery thread
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

        /** Reader loop thread: a read finished and its rows are not yet offered to any inbox (phase 3 §2.2) */
        default void afterReadBeforePublish(String aggregateType, List<StoredEvent> rows) throws Exception {
        }

        /**
         * Reader loop thread, at the quiescent point: the subscription's inbox was just attached with cursor {@code C}
         * and watermark {@code W}. The attaching subscription's hook
         */
        default void onAttach(String subscriber, long cursor, long watermark) throws Exception {
        }

        /** Delivery thread: the catch-up reached the watermark and the attach request is about to be sent */
        default void beforeAttachRequest(String subscriber) throws Exception {
        }

        /** Delivery thread: between two bridge pages; {@code lowerBound} is the next page's (exclusive) lower bound */
        default void betweenBridgePages(String subscriber, long lowerBound) throws Exception {
        }

        /** Delivery thread: the subscription detached and catches up from {@code resumeFrom} */
        default void onDetach(String subscriber, String reason, long resumeFrom) throws Exception {
        }
    }
}
