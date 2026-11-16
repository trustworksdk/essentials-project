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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 §2.4: a filtered inbox counts matching rows only; a batch with no matching row is stored as watermark-only and
 * merged into a trailing one; counting until released, only the matching rows counted are released. The mutant
 * {@link WatermarkMutant#INBOX_COUNTS_UNMATCHED} counts every row.
 */
class FilteredInboxTest {
    private static final EventTypeFilter X = EventTypeFilter.of("X");

    @Test
    void only_matching_rows_count_against_the_bound() throws Exception {
        var inbox = new Inbox(3, false, X, false);
        assertThat(inbox.offer(batch(10, "X", "Y", "Y", "Y", "X"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.events()).isEqualTo(2);
        assertThat(inbox.offer(batch(11, "Y", "X", "Y"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.events()).isEqualTo(3);
        assertThat(inbox.offer(batch(12, "X"))).as("a fourth matching row exceeds the bound").isEqualTo(Inbox.Offer.FULL);
        assertThat(inbox.take(0).rows()).as("the batch is kept whole for the delivery thread").hasSize(5);
        assertThat(inbox.events()).isEqualTo(1);
    }

    @Test
    void a_zero_match_batch_is_stored_watermark_only_and_merged() throws Exception {
        var inbox = new Inbox(1, false, X, false);
        assertThat(inbox.offer(batch(10, "X"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(11, "Y", "Y"))).as("full, but nothing matches").isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(12, "Y"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.batches()).isEqualTo(2);
        assertThat(inbox.events()).isEqualTo(1);
        inbox.take(0);
        var merged = inbox.take(0);
        assertThat(merged.watermarkOnly()).isTrue();
        assertThat(merged.watermark()).isEqualTo(12);
    }

    @Test
    void counting_until_released_releases_matching_rows_only() throws Exception {
        var inbox = new Inbox(10, true, X, false);
        inbox.offer(batch(10, "X", "Y", "X"));
        inbox.take(0);
        assertThat(inbox.events()).isEqualTo(2);
        inbox.release(2);
        assertThat(inbox.events()).isZero();
    }

    @Test
    void the_mutant_counts_unmatched_rows() throws Exception {
        var inbox = new Inbox(2, false, X, true);
        assertThat(inbox.offer(batch(10, "Y", "Y"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(11, "Y"))).isEqualTo(Inbox.Offer.FULL);
    }

    @Test
    void an_all_filter_counts_every_row() throws Exception {
        var inbox = new Inbox(2, false, EventTypeFilter.all(), false);
        assertThat(inbox.offer(batch(10, "Y", "Y"))).isEqualTo(Inbox.Offer.ACCEPTED);
        assertThat(inbox.offer(batch(11, "Y"))).isEqualTo(Inbox.Offer.FULL);
    }

    private static long next = 1;

    private static Batch batch(long watermark, String... types) {
        var rows = new ArrayList<StoredEvent>();
        for (var type : types) {
            rows.add(new StoredEvent("T", next++, 0, UUID.randomUUID(), null, null, type, (short) 1, OffsetDateTime.now(), "a", "{}", "{}", null));
        }
        return new Batch(rows, watermark);
    }
}
