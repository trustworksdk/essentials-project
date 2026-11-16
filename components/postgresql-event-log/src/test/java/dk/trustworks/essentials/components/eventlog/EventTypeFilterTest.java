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

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** Phase 5 §1.2 */
class EventTypeFilterTest {
    @Test
    void all_matches_every_type() {
        assertThat(EventTypeFilter.all().matchesAll()).isTrue();
        assertThat(EventTypeFilter.all().matches("Anything")).isTrue();
    }

    @Test
    void of_matches_its_names_only() {
        var filter = EventTypeFilter.of("A", "B", "A");
        assertThat(filter.eventTypes()).containsExactlyInAnyOrder("A", "B");
        assertThat(filter.matchesAll()).isFalse();
        assertThat(filter.matches("A")).isTrue();
        assertThat(filter.matches("C")).isFalse();
    }

    @Test
    void refuses_no_names_and_blank_names() {
        assertThatThrownBy(EventTypeFilter::of).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventTypeFilter(Set.of(" "))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void is_immutable() {
        var names  = new java.util.HashSet<>(Set.of("A"));
        var filter = new EventTypeFilter(names);
        names.add("B");
        assertThat(filter.matches("B")).isFalse();
    }
}
