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

import java.sql.*;
import java.util.*;
import java.util.function.Predicate;

/**
 * Test seams of {@link Migration}: mutants, crash hooks and a fault that leaves an event type un-interned. Package-private;
 * production code runs with {@link #NONE}.
 *
 * @param mutants     the deliberate defects switched on
 * @param hooks       called at named points, on the connection doing the work; an exception thrown there is the injected
 *                    crash
 * @param internNames which event-type names get interned before a batch (a fault for {@code TYPE_JOIN_DROPS_ROW}'s test)
 */
record MigrationSeams(Set<MigrationMutant> mutants, Map<Hook, ConnectionHook> hooks, Predicate<String> internNames) {
    static final MigrationSeams NONE = new MigrationSeams(Set.of(), Map.of(), name -> true);

    MigrationSeams {
        mutants = mutants.isEmpty() ? Set.of() : EnumSet.copyOf(mutants);
        hooks = Map.copyOf(hooks);
    }

    boolean has(MigrationMutant mutant) {
        return mutants.contains(mutant);
    }

    void at(Hook hook, Connection connection) throws SQLException {
        var h = hooks.get(hook);
        if (h != null) {
            h.accept(connection);
        }
    }

    enum Hook {
        /** In a batch: after the insert and before the checkpoint (under {@code CHECKPOINT_OUTSIDE_BATCH}: after the committed checkpoint, before the insert) */
        BATCH_BEFORE_CHECKPOINT,
        /** In the cut-over: right after the {@code SHARE} locks are taken */
        CUTOVER_LOCKED,
        /** In the cut-over: after every step, before the commit */
        CUTOVER_BEFORE_COMMIT
    }

    @FunctionalInterface
    interface ConnectionHook {
        void accept(Connection connection) throws SQLException;
    }
}
