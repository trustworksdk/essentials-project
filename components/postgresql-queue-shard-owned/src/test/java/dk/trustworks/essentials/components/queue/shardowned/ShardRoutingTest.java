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

import org.junit.jupiter.api.*;

import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Key routing. A key's shard decides which consumer serves it, so an uneven mapping is an uneven
 * split of the work — and because the mapping is frozen for the life of a queue's data, it is worth
 * pinning rather than assuming.
 */
class ShardRoutingTest {

    private static final int UNITS = 64;
    private static final int KEYS  = 1_024;

    /**
     * Structured keys — a prefix and a counter, with or without a fixed tail — are what real callers
     * use, and they are exactly what a 31-polynomial hash spreads badly in its low bits.
     */
    static Object[][] structuredKeyShapes() {
        return new Object[][]{
                {"ORDER-<n>", (IntFunction<String>) index -> "ORDER-" + (1000 + index)},
                {"acct-<n>-EU", (IntFunction<String>) index -> "acct-" + index + "-EU"},
                {"zero-padded, step 64", (IntFunction<String>) index -> String.format("ORDER-%08d", index * 64)},
        };
    }

    @Test
    void structured_keys_spread_evenly_across_the_routing_space() {
        for (var shape : structuredKeyShapes()) {
            var label = (String) shape[0];
            @SuppressWarnings("unchecked")
            var keys = (IntFunction<String>) shape[1];

            var counts = new int[UNITS];
            for (var index = 0; index < KEYS; index++) {
                counts[ShardOwnedSchema.shardForKey(keys.apply(index), UNITS)]++;
            }

            var mean = (double) KEYS / UNITS;
            assertThat(unitsUsed(counts))
                    .as("%s must reach every unit, or those consumers get nothing", label)
                    .isEqualTo(UNITS);
            assertThat(busiestUnit(counts))
                    .as("%s must not overload a unit; mean is %.1f", label, mean)
                    .isLessThanOrEqualTo((int) (mean * 2));
        }
    }

    /**
     * The reason {@code shardForKey} mixes at all. This asserts the DEFECT in the obvious
     * implementation, so that if someone removes the mixer believing it to be decoration, the
     * comparison that justified it is still here and still fails.
     * <p>
     * {@code String.hashCode} is specified by the JDK, so these numbers do not drift.
     */
    @Test
    void the_unmixed_hash_is_measurably_worse_and_that_is_why_the_mixer_exists() {
        var worstUnmixedCoverage = UNITS;
        var worstUnmixedLoad     = 0;
        for (var shape : structuredKeyShapes()) {
            @SuppressWarnings("unchecked")
            var keys = (IntFunction<String>) shape[1];
            var counts = new int[UNITS];
            for (var index = 0; index < KEYS; index++) {
                counts[Math.floorMod(keys.apply(index).hashCode(), UNITS)]++;
            }
            worstUnmixedCoverage = Math.min(worstUnmixedCoverage, unitsUsed(counts));
            worstUnmixedLoad = Math.max(worstUnmixedLoad, busiestUnit(counts));
        }
        assertThat(worstUnmixedCoverage)
                .as("taking the low bits directly leaves part of the routing space unreachable")
                .isLessThan(UNITS);
        assertThat(worstUnmixedLoad)
                .as("and overloads its busiest unit well past the mean of %d", KEYS / UNITS)
                .isGreaterThan((int) (2.0 * KEYS / UNITS));
    }

    @Test
    void a_key_always_routes_to_the_same_unit() {
        for (var index = 0; index < 1_000; index++) {
            var key   = "key-" + index;
            var first = ShardOwnedSchema.shardForKey(key, UNITS);
            assertThat(ShardOwnedSchema.shardForKey(key, UNITS))
                    .as("routing must be stable, or a key's ordering breaks across restarts")
                    .isEqualTo(first);
            assertThat(first).isBetween(0, UNITS - 1);
        }
    }

    private static int unitsUsed(int[] counts) {
        var used = 0;
        for (var count : counts) {
            if (count > 0) {
                used++;
            }
        }
        return used;
    }

    private static int busiestUnit(int[] counts) {
        var busiest = 0;
        for (var count : counts) {
            busiest = Math.max(busiest, count);
        }
        return busiest;
    }
}
