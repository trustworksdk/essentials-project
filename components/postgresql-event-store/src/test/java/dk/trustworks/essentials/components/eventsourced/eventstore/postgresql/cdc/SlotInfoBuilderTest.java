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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgReplicationSlots.SlotInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SlotInfo}'s fifteen-parameter constructor is nine {@link String}s in a row — the shape where a transposition
 * compiles, passes review, and then shows up as a slot whose {@code walStatus} reads {@code "logical"}.
 * <p>
 * The comparison runs <strong>field by field via reflection</strong> with a distinct value per field, so that swapping
 * any two setters in the builder fails this test.
 */
class SlotInfoBuilderTest {

    private static final String  SLOT_NAME           = "slot_name_value";
    private static final String  SLOT_TYPE           = "logical";
    private static final String  PLUGIN              = "pgoutput";
    private static final String  DATABASE            = "database_value";
    private static final Integer ACTIVE_PID          = 4711;
    private static final boolean TEMPORARY           = true;
    private static final String  RESTART_LSN         = "0/16B3748";
    private static final String  CONFIRMED_FLUSH_LSN = "0/16B3780";
    private static final String  WAL_STATUS          = "reserved";
    private static final Long    SAFE_WAL_SIZE       = 1_234_567L;
    private static final String  INACTIVE_SINCE      = "2026-08-23T12:00:00Z";
    private static final String  CONFLICTING         = "f";
    private static final String  INVALIDATION_REASON = "wal_removed";
    // temporary/failover/synced are three boolean-ish fields over two values, so they cannot all differ unless one
    // is null. synced being null is not a contrivance: PostgreSQL only added the column in 17, and a row read from
    // an older server genuinely has no value for it.
    private static final Boolean FAILOVER            = Boolean.FALSE;
    private static final Boolean SYNCED              = null;

    @SuppressWarnings("removal")
    private static SlotInfo viaConstructor() {
        return new SlotInfo(SLOT_NAME,
                            SLOT_TYPE,
                            PLUGIN,
                            DATABASE,
                            ACTIVE_PID,
                            TEMPORARY,
                            RESTART_LSN,
                            CONFIRMED_FLUSH_LSN,
                            WAL_STATUS,
                            SAFE_WAL_SIZE,
                            INACTIVE_SINCE,
                            CONFLICTING,
                            INVALIDATION_REASON,
                            FAILOVER,
                            SYNCED);
    }

    private static SlotInfo viaBuilder() {
        return SlotInfo.builder()
                       .setSlotName(SLOT_NAME)
                       .setSlotType(SLOT_TYPE)
                       .setPlugin(PLUGIN)
                       .setDatabase(DATABASE)
                       .setActivePid(ACTIVE_PID)
                       .setTemporary(TEMPORARY)
                       .setRestartLsn(RESTART_LSN)
                       .setConfirmedFlushLsn(CONFIRMED_FLUSH_LSN)
                       .setWalStatus(WAL_STATUS)
                       .setSafeWalSize(SAFE_WAL_SIZE)
                       .setInactiveSince(INACTIVE_SINCE)
                       .setConflicting(CONFLICTING)
                       .setInvalidationReason(INVALIDATION_REASON)
                       .setFailover(FAILOVER)
                       .setSynced(SYNCED)
                       .build();
    }

    private static Map<String, Object> fieldsOf(SlotInfo slotInfo) {
        var values = new LinkedHashMap<String, Object>();
        for (var field : SlotInfo.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                values.put(field.getName(), field.get(slotInfo));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not read field " + field.getName(), e);
            }
        }
        return values;
    }

    @Test
    void test_the_builder_populates_every_field_exactly_as_the_deprecated_constructor_does() {
        var fromConstructor = fieldsOf(viaConstructor());
        var fromBuilder     = fieldsOf(viaBuilder());

        assertThat(fromBuilder.keySet()).isEqualTo(fromConstructor.keySet());
        fromConstructor.forEach((name, expected) ->
                                        assertThat(fromBuilder.get(name))
                                                .as("field '%s'", name)
                                                .isEqualTo(expected));
    }

    @Test
    void test_every_field_carries_a_distinct_value_so_a_transposition_cannot_pass_unnoticed() {
        // Guards the test above rather than the production code: two fields sharing a value would make swapping
        // exactly those two invisible to the comparison.
        assertThat(fieldsOf(viaConstructor()).values())
                .as("two fields sharing a value would make a transposition of those two invisible")
                .doesNotHaveDuplicates();
    }

    @Test
    void test_the_derived_predicates_read_the_fields_the_builder_set() {
        assertThat(viaBuilder().isLogical()).isTrue();
        assertThat(viaBuilder().isActive()).isTrue();

        var physicalAndInactive = SlotInfo.builder()
                                          .setSlotName(SLOT_NAME)
                                          .setSlotType("physical")
                                          .build();
        assertThat(physicalAndInactive.isLogical()).isFalse();
        assertThat(physicalAndInactive.isActive()).isFalse();
    }
}
