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

package dk.trustworks.essentials.components.queue.shardowned;

import java.util.*;

/**
 * Finds a key that the ordered lane routes to a given unit, for the tests that need a message on a unit chosen up front
 * (the unit another key is stuck on, the unit a test holds). A shared, bounded search: it tries a bounded number of
 * candidates and fails with an {@link AssertionError} naming the unit, so a routing change that leaves the unit
 * unreachable fails the test instead of hanging it.
 */
final class OrderedUnitKeys {
    /**
     * How many candidates ({@code prefix + 0} up to {@code prefix + (CANDIDATES - 1)}) a search tries before it gives up.
     */
    static final int CANDIDATES = 100_000;

    private OrderedUnitKeys() {
    }

    /**
     * The first key {@code prefix + n}, other than each of {@code others}, that
     * {@link ShardOwnedSchema#unitForKey(String, int)} routes to {@code unit} in a routing space of {@code units}; when a
     * key of {@code others} routes to {@code unit} too, its messages and the result's are served by the same owner. Two
     * keys of one unit besides a given one are {@code a = keyInUnit(p, u, n, given)} and
     * {@code keyInUnit(p, u, n, given, a)}.
     *
     * @param others the keys the result must differ from; none to take the first key that routes to {@code unit}
     * @throws AssertionError when {@code unit} lies outside the routing space, or none of the {@link #CANDIDATES} candidates
     *                        other than {@code others} routes to it
     */
    static String keyInUnit(String prefix, int unit, int units, String... others) {
        if (unit < 0 || unit >= units) {
            throw new AssertionError("Ordered unit " + unit + " is outside the routing space of " + units + " units");
        }
        var excluded = Arrays.asList(others);
        for (var index = 0; index < CANDIDATES; index++) {
            var candidate = prefix + index;
            if (!excluded.contains(candidate) && ShardOwnedSchema.unitForKey(candidate, units) == unit) {
                return candidate;
            }
        }
        throw new AssertionError("None of the keys " + prefix + "0 to " + prefix + (CANDIDATES - 1)
                                 + (excluded.isEmpty() ? "" : " other than " + String.join(", ", excluded))
                                 + " routes to ordered unit " + unit + " of " + units);
    }
}
