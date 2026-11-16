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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 §2.2, §2.3: held-back rows in {@code global_order} order, released at a watermark, dropped at or below a
 * range read's ceiling, each removal releasing the row in the inbox that counts it.
 */
class HeldBackTest {
    @Test
    void rows_are_released_in_global_order_up_to_the_watermark() {
        var held = new HeldBack();
        held.add(entry(7, null));
        held.add(entry(3, null));
        held.add(entry(5, null));
        assertThat(held.lowest()).isEqualTo(3);
        assertThat(held.releaseUpTo(5)).extracting(HeldBack.Entry::globalOrder).containsExactly(3L, 5L);
        assertThat(held.size()).isEqualTo(1);
        assertThat(held.releaseUpTo(6)).isEmpty();
        assertThat(held.releaseUpTo(7)).extracting(HeldBack.Entry::globalOrder).containsExactly(7L);
        assertThat(held.isEmpty()).isTrue();
    }

    @Test
    void dropping_at_or_below_an_order_releases_each_dropped_row_in_its_inbox() throws Exception {
        var inbox = new Inbox(10, true);
        inbox.offer(new Batch(java.util.List.of(row(1), row(2), row(3)), 0));
        inbox.take(0);
        var held = new HeldBack();
        held.add(entry(1, inbox));
        held.add(entry(2, inbox));
        held.add(entry(3, inbox));
        assertThat(held.dropUpTo(2)).isEqualTo(2);
        assertThat(inbox.events()).isEqualTo(1);
        assertThat(held.lowest()).isEqualTo(3);
    }

    @Test
    void a_row_held_twice_is_held_once_and_the_newcomer_released() throws Exception {
        var inbox = new Inbox(10, true);
        inbox.offer(new Batch(java.util.List.of(row(4), row(4)), 0));
        inbox.take(0);
        var held = new HeldBack();
        held.add(entry(4, inbox));
        held.add(entry(4, inbox));
        assertThat(held.size()).isEqualTo(1);
        assertThat(inbox.events()).isEqualTo(1);
    }

    @Test
    void clear_forgets_without_releasing() throws Exception {
        var inbox = new Inbox(10, true);
        inbox.offer(new Batch(java.util.List.of(row(1)), 0));
        inbox.take(0);
        var held = new HeldBack();
        held.add(entry(1, inbox));
        held.clear();
        assertThat(held.isEmpty()).isTrue();
        assertThat(inbox.events()).as("a detach drops the inbox with the rows").isEqualTo(1);
    }

    private static HeldBack.Entry entry(long globalOrder, Inbox inbox) {
        return new HeldBack.Entry(row(globalOrder), inbox);
    }

    private static StoredEvent row(long globalOrder) {
        return new StoredEvent("T", globalOrder, 0, UUID.randomUUID(), null, null, "E", 1, OffsetDateTime.now(), "a", "{}", "{}", null);
    }
}
