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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.foundation.types.TenantId;
import org.junit.jupiter.api.Test;

import java.util.*;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §9.1 {@code MigrationCopyIT}: {@code jsonb} and {@code json} sources, {@code text} and {@code uuid} ids,
 * tenants, causation and correlation, three types; copied, cut over, and equal over every column (§2.3, §2.4)
 */
class MigrationCopyIT extends AbstractMigrationIT {
    private OldEngine          accounts, orders, users;
    private VerificationReport online;

    private void build() throws Exception {
        accounts = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, "Accounts");
        orders = new OldEngine(IdentifierColumnType.UUID, JSONColumnType.JSON, "Orders");
        users = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSON, "Users");
        accounts.tenant = TenantId.of("tenant-1");
        accounts.streams("Accounts", 5, 7);
        accounts.tenant = null;
        accounts.streams("Accounts", 3, 4);
        orders.streams("Orders", 6, 9);
        users.streams("Users", 4, 5);
        // a cause the event log cannot hold: copied as null and counted (§2.4, decision 5)
        execute("INSERT INTO " + users.table("Users") + " (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id, event_type, event_revision, timestamp, " +
                        "event_payload, event_metadata, tenant) SELECT aggregate_id || '-x', 0, gen_random_uuid()::text, 'not-a-uuid', correlation_id, event_type, " +
                        "event_revision, timestamp, event_payload, event_metadata, tenant FROM " + users.table("Users") + " LIMIT 1");
    }

    /** prepare, one epoch, more appends, cut-over; @return the differing rows per type */
    private Map<String, Long> copy(MigrationSeams seams) throws Exception {
        build();
        var migration = migration(accounts, orders, users);
        migration.seams(seams);
        var report = migration.prepare();
        assertThat(report.table("Users").orElseThrow().nonUuidCauses()).isEqualTo(1);
        assertThat(report.table("Orders").orElseThrow().columnTypes()).containsEntry("aggregate_id", "uuid").containsEntry("event_payload", "json");
        assertThat(report.table("Accounts").orElseThrow().columnTypes()).containsEntry("event_payload", "jsonb");
        migration.copyEpoch();
        online = migration.verify();
        orders.streams("Orders", 2, 3);
        migration.cutover();
        var differing = new LinkedHashMap<String, Long>();
        differing.put("Accounts", differingRows(accounts, "Accounts", oldMax(accounts, "Accounts")));
        differing.put("Orders", differingRows(orders, "Orders", oldMax(orders, "Orders")));
        differing.put("Users", differingRows(users, "Users", oldMax(users, "Users")));
        return differing;
    }

    @Test
    void every_row_is_copied_equal_over_every_column() throws Exception {
        assertThat(copy(MigrationSeams.NONE)).containsOnly(Map.entry("Accounts", 0L), Map.entry("Orders", 0L), Map.entry("Users", 0L));
        assertThat(newCount("Accounts")).isEqualTo(47);
        assertThat(newCount("Orders")).isEqualTo(60);
        assertThat(newCount("Users")).isEqualTo(21);
        // the online verification covered the epoch's ranges, the cut-over's the delta; both equal (§6.2)
        assertThat(online.equal()).isTrue();
        assertThat(online.type("Orders").orElseThrow().rows()).isEqualTo(54);
        assertThat(online.type("Accounts").orElseThrow().windows()).isNotEmpty();
        assertThat(queryLong("SELECT count(*) FROM el_users_events WHERE caused_by_event_id IS NULL")).isEqualTo(1);
        assertThat(queryLong("SELECT count(*) FROM el_accounts_events WHERE tenant = 'tenant-1'")).isEqualTo(35);
        // the json source's text is kept byte for byte, the jsonb source's is its ::text rendering (§2.3)
        assertThat(queryLong("SELECT count(*) FROM el_orders_events e JOIN orders_events o USING (global_order) WHERE e.event_payload::text <> o.event_payload::text")).isZero();
        assertThat(queryLong("SELECT count(*) FROM el_orders_events WHERE event_payload::text LIKE '%\": %'")).as("Jackson's compact json, not jsonb's rendering").isZero();
        for (var type : List.of("Accounts", "Orders", "Users")) {
            assertHeadsConsistent(type);
        }
        // the engine reads every copied stream (uuid ids as lower-case text)
        var anOrder = queryLong("SELECT count(*) FROM orders_events WHERE aggregate_id = (SELECT aggregate_id FROM orders_events LIMIT 1)");
        var id      = new String[1];
        try (var c = dataSource.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT aggregate_id::text FROM orders_events LIMIT 1")) {
            r.next();
            id[0] = r.getString(1);
        }
        var stream = eventLog.loadStream("Orders", id[0]);
        assertThat(stream).hasSize((int) anOrder);
        assertThat(stream.getFirst().eventType()).endsWith("Opened");
    }

    @Test
    void must_fail_copy_PAYLOAD_VIA_JSONB() throws Exception {
        var differing = copy(mutants(PAYLOAD_VIA_JSONB, CUTOVER_WITHOUT_VERIFY));
        assertThat(differing.get("Orders")).as("a json source re-rendered through jsonb differs").isPositive();
        assertThat(differing.get("Accounts")).as("a jsonb source is unchanged by the round trip").isZero();
    }

    @Test
    void a_row_whose_type_has_no_id_aborts_the_batch() throws Exception {
        build();
        var migration = migration(accounts, orders, users);
        migration.prepare();
        migration.seams(seams(Set.of(), Map.of(), name -> !name.endsWith("Amended")));
        assertThatThrownBy(migration::copyEpoch).isInstanceOf(IllegalStateException.class).hasMessageContaining("dropped rows from the join");
        // the failed batch committed nothing, the checkpoint included
        var status = migration.status("Accounts").orElseThrow();
        assertThat(differingRows(accounts, "Accounts", status.copiedThrough())).isZero();
        assertThat(status.copiedThrough()).isZero();
        // with every type interned, the rerun copies everything
        migration.seams(MigrationSeams.NONE);
        migration.copyEpoch();
        assertThat(differingRows(accounts, "Accounts", oldMax(accounts, "Accounts"))).isZero();
    }

    @Test
    void must_fail_copy_TYPE_JOIN_DROPS_ROW() throws Exception {
        build();
        var migration = migration(accounts, orders, users);
        migration.prepare();
        migration.seams(seams(Set.of(TYPE_JOIN_DROPS_ROW), Map.of(), name -> !name.endsWith("Amended")));
        migration.copyEpoch();
        var status = migration.status("Accounts").orElseThrow();
        assertThat(status.copiedThrough()).isEqualTo(oldMax(accounts, "Accounts"));
        assertThat(differingRows(accounts, "Accounts", status.copiedThrough())).as("rows dropped silently below the checkpoint").isPositive();
    }

    // ------------------------------------------------------------------ verification (§6, §9.1)

    @Test
    void verification_finds_a_payload_re_rendered_through_jsonb_and_the_cut_over_rolls_back() throws Exception {
        build();
        var migration = migration(accounts, orders, users);
        migration.seams(mutants(PAYLOAD_VIA_JSONB));
        migration.prepare();
        migration.copyEpoch();
        var report = migration.verify();
        assertThat(report.equal()).isFalse();
        assertThat(report.type("Accounts").orElseThrow().equal()).as("a jsonb source is unchanged by the round trip").isTrue();
        var ordersVerification = report.type("Orders").orElseThrow();
        assertThat(ordersVerification.differingRows()).hasSize((int) differingRows(orders, "Orders", oldMax(orders, "Orders")) / 2) // the oracle counts a changed row on each side
                                                      .allSatisfy(r -> assertThat(r.kind()).isEqualTo(VerificationReport.RowDifference.Kind.DIFFERENT));
        assertThat(report.type("Users").orElseThrow().equal()).isFalse();

        var copiedThrough = migration.status("Orders").orElseThrow().copiedThrough();
        orders.streams("Orders", 1, 2);
        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class)
                                              .satisfies(e -> assertThat(((VerificationFailedException) e).report().type("Orders").orElseThrow().from()).isZero());
        var status = migration.status("Orders").orElseThrow();
        assertThat(status.state()).isEqualTo(MigrationStatus.State.COPYING);
        assertThat(status.copiedThrough()).isEqualTo(copiedThrough);
        assertThat(newCount("Orders")).as("the delta's rows rolled back with the cut-over").isEqualTo(54);
    }

    @Test
    void verification_finds_rows_a_type_join_dropped_and_the_cut_over_rolls_back() throws Exception {
        build();
        var migration = migration(accounts, orders, users);
        migration.prepare();
        migration.seams(seams(Set.of(TYPE_JOIN_DROPS_ROW), Map.of(), name -> !name.endsWith("Amended")));
        migration.copyEpoch();
        var accountsVerification = migration.verify().type("Accounts").orElseThrow();
        assertThat(accountsVerification.equal()).isFalse();
        assertThat(accountsVerification.differingRows()).hasSize((int) differingRows(accounts, "Accounts", oldMax(accounts, "Accounts")))
                                                        .allSatisfy(r -> assertThat(r.kind()).isEqualTo(VerificationReport.RowDifference.Kind.MISSING_IN_EVENT_LOG));
        migration.seams(MigrationSeams.NONE);
        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class).hasMessageContaining("MISSING_IN_EVENT_LOG");
        assertThat(migration.status("Accounts").orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
    }

    @Test
    void must_fail_verify_VERIFY_WITHOUT_PAYLOAD() throws Exception {
        var differing = copy(mutants(PAYLOAD_VIA_JSONB, VERIFY_WITHOUT_PAYLOAD));
        assertThat(online.equal()).as("a hash without the payload is blind to the re-rendering").isTrue();
        assertThat(differing.get("Orders")).as("cut over with every Orders row differing").isPositive();
    }
}
