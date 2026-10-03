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

package dk.trustworks.essentials.components.queue.shardowned.spi;

import dk.trustworks.essentials.components.queue.shardowned.spi.MessageId.Lane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * The text form of a message id.
 * <p>
 * It exists because {@code getMessage(MessageId)} was reachable only from Java: an id that renders as
 * {@code MessageId[lane=UNORDERED, shard=3, sequence=1042]} cannot be a URL path segment, cannot be
 * pasted into {@code psql}, and cannot be quoted in a support ticket. Everything below is about that
 * round trip surviving the places an id actually travels through.
 */
class MessageIdTest {

    @Test
    void an_id_survives_a_round_trip_through_its_text_form() {
        var id = new MessageId(Lane.UNORDERED, 3, 1042L);

        assertThat(id.toString()).isEqualTo("u-3-1042");
        assertThat(MessageId.parse(id.toString())).isEqualTo(id);
    }

    @Test
    void both_lanes_have_a_distinct_code() {
        assertThat(new MessageId(Lane.UNORDERED, 0, 1L).toString()).isEqualTo("u-0-1");
        assertThat(new MessageId(Lane.ORDERED, 0, 1L).toString()).isEqualTo("o-0-1");
        assertThat(MessageId.parse("o-0-1").lane()).isEqualTo(Lane.ORDERED);
    }

    /**
     * The whole point of the format: what comes back from an HTTP response has to work as a path
     * segment without escaping, or every caller has to know to encode it and half of them will not.
     */
    @Test
    void the_text_form_needs_no_url_escaping() {
        var rendered = new MessageId(Lane.ORDERED, 12, 987_654_321L).toString();

        assertThat(rendered).matches("[a-z0-9-]+");
        assertThat(java.net.URLEncoder.encode(rendered, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(rendered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "u-3", "u-3-1042-extra", "x-3-1042", "unordered-3-1042", "u-three-1042", "u-3-many"})
    void junk_is_rejected_with_a_message_that_names_the_expected_shape(String junk) {
        assertThatThrownBy(() -> MessageId.parse(junk))
                .describedAs("'%s' must not parse", junk)
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A caller reaching this over HTTP hands us whatever it likes, and the difference between a 400 and
     * a 500 is whether the failure is an {@code IllegalArgumentException}. An
     * {@code ArrayIndexOutOfBoundsException} from an unguarded {@code split} would be neither, and the
     * admin API's exception handler would map it to a 500 — reporting the caller's typo as a server
     * fault.
     */
    @Test
    void a_malformed_id_is_an_illegal_argument_not_an_index_error() {
        assertThatThrownBy(() -> MessageId.parse("u"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<lane>-<shard>-<sequence>");
    }

    @Test
    void an_id_cannot_be_constructed_with_a_sequence_no_row_can_have() {
        assertThatThrownBy(() -> new MessageId(Lane.UNORDERED, 0, 0L))
                .describedAs("sequences come from a sequence generator and start at 1")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageId(Lane.UNORDERED, -1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
