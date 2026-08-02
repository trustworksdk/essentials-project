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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test over the two streaming WAL pre-filters.
 * <p>
 * {@link DefaultWalMessageFilter} (Jackson 2) and {@link Jackson3WalMessageFilter} (Jackson 3) are a deliberately
 * duplicated pair — streaming has to be preserved on both majors, and the token APIs differ. Duplication invites drift,
 * so rather than trusting each in isolation this asserts they return the <em>same</em> answer for every payload.
 * <p>
 * Both are exercised in every build regardless of the active Jackson flavor: each depends only on its own Jackson
 * streaming API, both of which the module compiles against.
 */
class WalMessageFilterFlavorParityTest {

    private static final Set<String> TRACKED = Set.of("orders_events");

    private final WalMessageFilter jackson2 = new DefaultWalMessageFilter(() -> TRACKED);
    private final WalMessageFilter jackson3 = new Jackson3WalMessageFilter(() -> TRACKED);

    static List<String> walPayloads() {
        return List.of(
                // tracked insert — must be persisted
                """
                {"change":[{"kind":"INSERT","schema":"public","table":"orders_events"}]}""",
                // untracked table
                """
                {"change":[{"kind":"insert","schema":"public","table":"products_events"}]}""",
                // tracked table but not an insert
                """
                {"change":[{"kind":"update","schema":"public","table":"orders_events"}]}""",
                // several changes, only the last one relevant
                """
                {"change":[{"kind":"update","table":"orders_events"},{"kind":"delete","table":"orders_events"},\
                {"kind":"INSERT","table":"orders_events"}]}""",
                // nested objects before the interesting fields, to exercise skipChildren
                """
                {"change":[{"columnvalues":[{"nested":{"deep":[1,2,3]}}],"kind":"insert","table":"orders_events"}]}""",
                // no change array at all
                """
                {"xid":1234,"nextlsn":"0/1633C00"}""",
                // empty change array
                """
                {"change":[]}""",
                // mixed case table name
                """
                {"change":[{"kind":"insert","table":"ORDERS_EVENTS"}]}""",
                // malformed JSON — both must decline rather than throw
                """
                {"change":[{"kind":"insert","table":"orders_events\"""");
    }

    @ParameterizedTest
    @MethodSource("walPayloads")
    void both_flavors_agree_on_string_payloads(String walJson) {
        assertThat(jackson3.shouldPersist(walJson))
                .as("Jackson 3 filter disagreed with Jackson 2 for payload: %s", walJson)
                .isEqualTo(jackson2.shouldPersist(walJson));
    }

    @ParameterizedTest
    @MethodSource("walPayloads")
    void both_flavors_agree_on_byte_payloads(String walJson) {
        var bytes = walJson.getBytes(StandardCharsets.UTF_8);

        assertThat(jackson3.shouldPersist(bytes))
                .as("Jackson 3 filter disagreed with Jackson 2 for byte payload: %s", walJson)
                .isEqualTo(jackson2.shouldPersist(bytes));
    }

    /** Keeps the parity assertions honest — if both filters rejected everything, agreement would be meaningless. */
    @Test
    void the_payload_set_covers_both_outcomes() {
        var results = walPayloads().stream().map(jackson2::shouldPersist).distinct().toList();

        assertThat(results).as("payloads must produce both persist and skip decisions").hasSize(2);
    }

    @Test
    void an_empty_tracked_table_set_skips_everything_on_both_flavors() {
        var noTables2 = new DefaultWalMessageFilter(() -> Set.<String>of());
        var noTables3 = new Jackson3WalMessageFilter(() -> Set.<String>of());
        var tracked   = """
                        {"change":[{"kind":"insert","table":"orders_events"}]}""";

        assertThat(noTables2.shouldPersist(tracked)).isFalse();
        assertThat(noTables3.shouldPersist(tracked)).isFalse();
    }
}
