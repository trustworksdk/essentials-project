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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId;
import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId.Lane;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class QueueEntryIdCodecTest {

    @Test
    void an_entry_id_round_trips() {
        var decoded = QueueEntryIdCodec.decode(
                QueueEntryIdCodec.encode(QueueName.of("orders"), new MessageId(Lane.UNORDERED, 3, 1042L)));

        assertThat(decoded.queueName().toString()).isEqualTo("orders");
        assertThat(decoded.messageId()).isEqualTo(new MessageId(Lane.UNORDERED, 3, 1042L));
    }

    @Test
    void the_encoded_form_is_the_documented_one() {
        assertThat(QueueEntryIdCodec.encode(QueueName.of("orders"), new MessageId(Lane.ORDERED, 0, 7L)).toString())
                .isEqualTo("orders:o-0-7");
    }

    /**
     * The collision that would have broken the whole point of the adapter.
     * <p>
     * {@code InboxName.asQueueName()} produces {@code Inbox:orders} and the outbox equivalent
     * {@code Outbox:orders} — so the queues this adapter exists to serve <em>all</em> contain the
     * separator. Splitting on the first colon truncates every one of them to {@code "Inbox"} or
     * {@code "Outbox"}, and the symptom is "no such message" against a queue that does exist.
     * <p>
     * The names are taken from the real {@code InboxName}/{@code OutboxName} rather than written as
     * string literals, so that a change to how they are derived breaks this test rather than
     * production.
     */
    @Test
    void an_inbox_or_outbox_queue_name_survives_although_it_contains_the_separator() {
        for (var queueName : new QueueName[]{InboxName.of("orders").asQueueName(),
                                             OutboxName.of("orders").asQueueName()}) {
            assertThat(queueName.toString())
                    .describedAs("the premise: these names really do contain the separator")
                    .contains(":");

            var decoded = QueueEntryIdCodec.decode(
                    QueueEntryIdCodec.encode(queueName, new MessageId(Lane.UNORDERED, 1, 5L)));

            assertThat(decoded.queueName().toString())
                    .describedAs("%s must survive intact", queueName)
                    .isEqualTo(queueName.toString());
            assertThat(decoded.messageId()).isEqualTo(new MessageId(Lane.UNORDERED, 1, 5L));
        }
    }

    /**
     * An id from {@code PostgresqlDurableQueues} is a UUID. It must fail here rather than be
     * half-understood — an adapter that read the tail of a UUID as a message id would address some
     * arbitrary row.
     */
    @Test
    void an_id_from_a_different_implementation_is_rejected() {
        assertThatThrownBy(() -> QueueEntryIdCodec.decode(QueueEntryId.of("f8d1e0c2-2f4a-4f0e-9a3f-1c2d3e4f5a6b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<queueName>");
    }

    @Test
    void junk_is_rejected() {
        assertThatThrownBy(() -> QueueEntryIdCodec.decode(QueueEntryId.of("orders:")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueEntryIdCodec.decode(QueueEntryId.of(":u-0-1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueEntryIdCodec.decode(QueueEntryId.of("orders:not-an-id")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
