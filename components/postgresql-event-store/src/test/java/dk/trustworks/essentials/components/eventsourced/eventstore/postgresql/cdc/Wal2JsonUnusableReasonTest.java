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

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class Wal2JsonUnusableReasonTest {
    @Test
    void a_server_whose_output_plugin_allow_list_leaves_wal2json_out_is_named_with_both_ways_to_add_it() {
        // PostgreSQL 17.12 / 18: the reason used to be a bare "wal2json plugin not usable"
        var reason = Wal2JsonLogicalDecodingPlugin.unusableReason(Optional.of("pgoutput, test_decoding"));
        assertThat(reason).contains("output_plugin_libraries ('pgoutput, test_decoding')")
                          .contains("output_plugin_libraries = 'pgoutput, test_decoding, wal2json'")
                          .contains("ALTER SYSTEM SET output_plugin_libraries = 'pgoutput', 'test_decoding', 'wal2json'")
                          .contains("pg_reload_conf()");
    }

    @Test
    void a_server_without_the_setting_or_allowing_wal2json_gets_the_plain_reason() {
        assertThat(Wal2JsonLogicalDecodingPlugin.unusableReason(Optional.empty())).isEqualTo("wal2json plugin not usable");
        assertThat(Wal2JsonLogicalDecodingPlugin.unusableReason(Optional.of("pgoutput, wal2json"))).isEqualTo("wal2json plugin not usable");
    }
}
