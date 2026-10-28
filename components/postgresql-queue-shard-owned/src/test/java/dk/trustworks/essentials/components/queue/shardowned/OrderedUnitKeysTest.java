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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.BitSet;

import static org.assertj.core.api.Assertions.*;

class OrderedUnitKeysTest {
    @Test
    void finds_a_key_the_ordered_lane_routes_to_the_unit() {
        for (var unit = 0; unit < ShardOwnedSchema.ORDERED_UNITS; unit++) {
            var key = OrderedUnitKeys.keyInUnit("YOUNG-", unit, ShardOwnedSchema.ORDERED_UNITS);

            assertThat(key).startsWith("YOUNG-");
            assertThat(ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS)).isEqualTo(unit);
        }
    }

    @Test
    void never_returns_the_key_it_is_told_to_avoid() {
        var other = OrderedUnitKeys.keyInUnit("YOUNG-", 7, ShardOwnedSchema.ORDERED_UNITS);

        var key = OrderedUnitKeys.keyInUnit("YOUNG-", 7, ShardOwnedSchema.ORDERED_UNITS, other);

        assertThat(key).isNotEqualTo(other);
        assertThat(ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS)).isEqualTo(7);
    }

    @Test
    void never_returns_any_of_several_keys_it_is_told_to_avoid() {
        var first  = OrderedUnitKeys.keyInUnit("YOUNG-", 7, ShardOwnedSchema.ORDERED_UNITS);
        var second = OrderedUnitKeys.keyInUnit("YOUNG-", 7, ShardOwnedSchema.ORDERED_UNITS, first);

        var key = OrderedUnitKeys.keyInUnit("YOUNG-", 7, ShardOwnedSchema.ORDERED_UNITS, first, second);

        assertThat(key).isNotIn(first, second);
        assertThat(ShardOwnedSchema.unitForKey(key, ShardOwnedSchema.ORDERED_UNITS)).isEqualTo(7);
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fails_naming_the_unit_and_the_keys_it_avoided_when_no_other_candidate_routes_to_it() {
        var unreachable = unitNoCandidateRoutesTo();
        assertThatThrownBy(() -> OrderedUnitKeys.keyInUnit("X", unreachable.unit(), unreachable.units(), "X1", "X2"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("other than X1, X2 routes to ordered unit " + unreachable.unit() + " of " + unreachable.units());
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fails_naming_the_unit_when_the_unit_is_outside_the_routing_space() {
        assertThatThrownBy(() -> OrderedUnitKeys.keyInUnit("YOUNG-", ShardOwnedSchema.ORDERED_UNITS, ShardOwnedSchema.ORDERED_UNITS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Ordered unit " + ShardOwnedSchema.ORDERED_UNITS);
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void fails_naming_the_unit_when_no_candidate_routes_to_it() {
        var unreachable = unitNoCandidateRoutesTo();
        assertThatThrownBy(() -> OrderedUnitKeys.keyInUnit("X", unreachable.unit(), unreachable.units()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("routes to ordered unit " + unreachable.unit() + " of " + unreachable.units());
    }

    private record UnitInSpace(int unit, int units) {
    }

    /**
     * A unit of a routing space far wider than {@link OrderedUnitKeys#CANDIDATES}: the candidates cover at most that many
     * units, so at least one unit of the space is reached by none of them.
     */
    private static UnitInSpace unitNoCandidateRoutesTo() {
        var units   = OrderedUnitKeys.CANDIDATES * 4;
        var reached = new BitSet(units);
        for (var index = 0; index < OrderedUnitKeys.CANDIDATES; index++) {
            reached.set(ShardOwnedSchema.unitForKey("X" + index, units));
        }
        return new UnitInSpace(reached.nextClearBit(0), units);
    }
}
