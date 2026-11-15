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
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 §2.3: the inbox's bound counts events, an empty inbox accepts any batch, watermark-only batches coalesce
 * in order, and a closed inbox accepts and hands out nothing.
 */
class InboxTest {
    private static final long NOW = 0;

    @Test
    void the_bound_counts_events_not_batches() throws Exception {
        var inbox = new Inbox(5);
        assertThat(inbox.offer(batch(10, 1, 2))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(10, 3, 4, 5))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.events()).isEqualTo(5);
        assertThat(inbox.offer(batch(10, 6))).as("a sixth event exceeds the bound").isEqualTo(Inbox.Offer.FULL);
        assertThat(inbox.take(NOW).rows()).hasSize(2);
        assertThat(inbox.offer(batch(10, 6, 7))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.events()).isEqualTo(5);
    }

    @Test
    void an_empty_inbox_accepts_a_batch_larger_than_the_bound() throws Exception {
        var inbox = new Inbox(2);
        assertThat(inbox.offer(batch(10, 1, 2, 3, 4))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(10, 5))).isEqualTo(Inbox.Offer.FULL);
        inbox.take(NOW);
        assertThat(inbox.offer(batch(10, 5, 6, 7))).isEqualTo(Inbox.Offer.ACCEPTED);
    }

    @Test
    void watermark_only_batches_cost_no_capacity_and_coalesce_after_the_rows_they_cover() throws Exception {
        var inbox = new Inbox(2);
        assertThat(inbox.offer(batch(2, 1, 2))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(3))).as("full, but watermark-only").isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(5))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.batches()).as("the two watermark-only batches merged").isEqualTo(2);
        assertThat(inbox.events()).isEqualTo(2);
        var first = inbox.take(NOW);
        assertThat(first.rows()).hasSize(2);
        assertThat(first.watermark()).isEqualTo(2);
        var second = inbox.take(NOW);
        assertThat(second.watermarkOnly()).isTrue();
        assertThat(second.watermark()).isEqualTo(5);
        assertThat(inbox.take(NOW)).isNull();
    }

    @Test
    void a_watermark_only_batch_does_not_merge_into_a_batch_with_rows() throws Exception {
        var inbox = new Inbox(10);
        inbox.offer(batch(3));
        inbox.offer(batch(4, 4));
        inbox.offer(batch(5));
        assertThat(inbox.batches()).isEqualTo(3);
        assertThat(inbox.take(NOW).watermark()).isEqualTo(3);
        assertThat(inbox.take(NOW).rows()).hasSize(1);
        assertThat(inbox.take(NOW).watermark()).isEqualTo(5);
    }

    @Test
    void close_discards_what_is_held_keeps_the_first_reason_and_refuses_offers() throws Exception {
        var inbox = new Inbox(10);
        inbox.offer(batch(2, 1, 2));
        assertThat(inbox.close(Inbox.CloseReason.OVERFLOW)).isTrue();
        assertThat(inbox.close(Inbox.CloseReason.STOPPED)).isFalse();
        assertThat(inbox.closeReason()).isEqualTo(Inbox.CloseReason.OVERFLOW);
        assertThat(inbox.events()).isZero();
        assertThat(inbox.take(TimeUnit.SECONDS.toNanos(5))).isNull();
        assertThat(inbox.offer(batch(3, 3))).isEqualTo(Inbox.Offer.CLOSED);
    }

    @Test
    void close_wakes_a_waiting_consumer() throws Exception {
        var inbox  = new Inbox(10);
        var waiter = Thread.ofVirtual().start(() -> {
            try {
                inbox.take(TimeUnit.SECONDS.toNanos(30));
            } catch (InterruptedException ignored) {
            }
        });
        while (!inbox.consumerIdle()) {
            Thread.onSpinWait();
        }
        inbox.close(Inbox.CloseReason.READER_RESTART);
        assertThat(waiter.join(java.time.Duration.ofSeconds(5))).isTrue();
        assertThat(inbox.consumerIdle()).isFalse();
    }

    private static Batch batch(long watermark, long... orders) {
        var rows = new ArrayList<StoredEvent>();
        for (var g : orders) {
            rows.add(new StoredEvent("T", g, 0, UUID.randomUUID(), null, null, "E", (short) 1, OffsetDateTime.now(), "a", "{}", "{}", null));
        }
        return new Batch(rows, watermark);
    }
}
