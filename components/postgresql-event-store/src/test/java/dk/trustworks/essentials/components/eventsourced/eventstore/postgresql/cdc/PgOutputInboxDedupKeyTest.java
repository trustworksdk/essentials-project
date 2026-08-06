/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.PgOutputProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.PgOutputToPersistedEventConverter;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CDC inbox dedups on {@code unique(slot_name, lsn)}. pgoutput reports every RELATION message at
 * LSN {@code 0/0}, so a raw-LSN key collapses the schema for all event-stream tables onto a single
 * row and leaves every table but one undecodable. These tests pin the qualification that prevents it.
 */
class PgOutputInboxDedupKeyTest {

    private final PgOutputLogicalDecodingPlugin plugin = plugin();

    @Test
    void relation_messages_for_different_relations_get_distinct_dedup_keys_despite_a_shared_lsn() {
        var keyForFirstRelation  = plugin.inboxDedupKey(relationMessage(16467), "0/0");
        var keyForSecondRelation = plugin.inboxDedupKey(relationMessage(16531), "0/0");

        assertThat(keyForFirstRelation)
                .as("two tables' schema messages both arrive at 0/0 and must not collide on the inbox dedup key")
                .isNotEqualTo(keyForSecondRelation);
    }

    @Test
    void the_dedup_key_of_a_relation_message_is_deterministic_so_a_reconnect_still_dedups() {
        assertThat(plugin.inboxDedupKey(relationMessage(16467), "0/0"))
                .isEqualTo(plugin.inboxDedupKey(relationMessage(16467), "0/0"));
    }

    @Test
    void insert_messages_keep_their_raw_lsn_as_the_dedup_key() {
        assertThat(plugin.inboxDedupKey(insertMessage(16467), "0/1A0C818")).isEqualTo("0/1A0C818");
    }

    @Test
    void a_payload_too_short_to_carry_a_relation_id_falls_back_to_the_raw_lsn() {
        assertThat(plugin.inboxDedupKey(new byte[]{'R'}, "0/0")).isEqualTo("0/0");
        assertThat(plugin.inboxDedupKey(new byte[0], "0/0")).isEqualTo("0/0");
        assertThat(plugin.inboxDedupKey(null, "0/0")).isEqualTo("0/0");
    }

    @Test
    void relation_is_reported_as_the_schema_carrying_message_type() {
        assertThat(plugin.schemaPayloadLeadingBytes()).containsExactly((int) 'R');
    }

    private static byte[] relationMessage(int relationId) {
        return typeMarkerFollowedByRelationId('R', relationId);
    }

    private static byte[] insertMessage(int relationId) {
        return typeMarkerFollowedByRelationId('I', relationId);
    }

    private static byte[] typeMarkerFollowedByRelationId(char type, int relationId) {
        return ByteBuffer.allocate(5).put((byte) type).putInt(relationId).array();
    }

    private static PgOutputLogicalDecodingPlugin plugin() {
        var properties = new PgOutputProperties();
        properties.setPublicationName("test_publication");
        properties.setProtoVersion(1);
        properties.setBinary(false);
        properties.setMessages(false);
        var converter = new PgOutputToPersistedEventConverter(
                EssentialsJSONEventSerializers.createForActiveJacksonFlavor(),
                table -> null);
        return new PgOutputLogicalDecodingPlugin(properties, converter);
    }
}
