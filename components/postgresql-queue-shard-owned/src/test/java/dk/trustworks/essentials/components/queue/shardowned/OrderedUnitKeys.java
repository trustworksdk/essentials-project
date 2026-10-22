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
     * The first key {@code prefix + n} that {@link ShardOwnedSchema#unitForKey(String, int)} routes to {@code unit} in a
     * routing space of {@code units}.
     *
     * @throws AssertionError when {@code unit} lies outside the routing space, or none of the {@link #CANDIDATES} candidates
     *                        routes to it
     */
    static String keyInUnit(String prefix, int unit, int units) {
        return keyInUnit(prefix, unit, units, null);
    }

    /**
     * The first key {@code prefix + n} other than {@code other} that {@link ShardOwnedSchema#unitForKey(String, int)} routes
     * to {@code unit} in a routing space of {@code units}; when {@code other} routes to {@code unit} too, the two keys'
     * messages are served by the same owner.
     *
     * @param other a key the result must differ from, or {@code null} for none
     * @throws AssertionError when {@code unit} lies outside the routing space, or none of the {@link #CANDIDATES} candidates
     *                        other than {@code other} routes to it
     */
    static String keyInUnit(String prefix, int unit, int units, String other) {
        if (unit < 0 || unit >= units) {
            throw new AssertionError("Ordered unit " + unit + " is outside the routing space of " + units + " units");
        }
        for (var index = 0; index < CANDIDATES; index++) {
            var candidate = prefix + index;
            if (!candidate.equals(other) && ShardOwnedSchema.unitForKey(candidate, units) == unit) {
                return candidate;
            }
        }
        throw new AssertionError("None of the keys " + prefix + "0 to " + prefix + (CANDIDATES - 1)
                                 + (other == null ? "" : " other than " + other)
                                 + " routes to ordered unit " + unit + " of " + units);
    }
}
