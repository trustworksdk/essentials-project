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

package dk.trustworks.essentials.components.queue.shardowned;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A message the charge before a release dead-letters ({@link OrderedShardOwner#chargeKeysInHandlerBeforeRelease}) blocks
 * its key in the releasing owner's memory at once, as a message dead-lettered by its own failure does: a later
 * {@code key_order} of that key is never dispatched past the dead letter by this owner, whether or not it dispatches again
 * before its next pass re-reads the blocks from the table. The charge used to leave the key unblocked here, safe only
 * because every owner it runs on dispatches nothing more.
 */
class OrderedShardOwnerReleaseChargeBlockTest {

    private static final int MAX_ATTEMPTS = 3;

    @Test
    void a_key_whose_message_the_charge_dead_letters_is_blocked_at_that_key_order_and_what_is_held_above_it_is_queued_for_the_dead_letter_table() throws Exception {
        var owner = owner();
        enterHandler(owner, "DEAD-LETTERED", 7L, 5L);
        enterHandler(owner, "CHARGED-ONLY", 9L, 2L);
        hold(owner, new ShardOwnedStorage.OrderedRow(8L, "DEAD-LETTERED", 6L, new byte[0], 0));
        hold(owner, new ShardOwnedStorage.OrderedRow(10L, "CHARGED-ONLY", 3L, new byte[0], 0));
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(7L, MAX_ATTEMPTS, 9L, 1));

        var charge = owner.chargeKeysInHandlerBeforeRelease(database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER);

        assertThat(charge.deadLetteredKeys()).as("premise: the charge dead-lettered exactly the message at the limit").containsExactly("DEAD-LETTERED");
        assertThat(database.moved).as("premise: the move was written").containsExactly(7L);
        assertThat(deadLetterBlocks(owner)).as("the key is blocked at the dead letter's key_order, the other key is not")
                                           .containsExactly(Map.entry("DEAD-LETTERED", 5L));
        assertThat(readyByKey(owner).getOrDefault("DEAD-LETTERED", new TreeMap<>())).as("nothing above the dead letter is left to dispatch")
                                                                                    .isEmpty();
        assertThat(readyByKey(owner).get("CHARGED-ONLY")).as("a key charged below the limit keeps its next message").containsOnlyKeys(3L);
        assertThat(pendingPoisonSeqs(owner)).as("the message held above the dead letter is queued for the dead-letter table").containsExactly(8L);
    }

    @Test
    void a_later_key_order_of_a_key_the_charge_dead_lettered_is_queued_for_the_dead_letter_table_when_it_is_read() throws Exception {
        var owner = owner();
        enterHandler(owner, "DEAD-LETTERED", 7L, 5L);
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(7L, MAX_ATTEMPTS));
        owner.chargeKeysInHandlerBeforeRelease(database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER);

        accept(owner, new ShardOwnedStorage.OrderedRow(11L, "DEAD-LETTERED", 6L, new byte[0], 0));

        assertThat(readyByKey(owner)).as("a message read behind the dead letter is not held for dispatch").doesNotContainKey("DEAD-LETTERED");
        assertThat(pendingPoisonSeqs(owner)).as("it is queued for the dead-letter table").containsExactly(11L);
    }

    /**
     * A move owed by the charge that moves nothing - the row no longer in the table, or no longer this owner's - makes no
     * dead letter, so the key is not blocked: blocked, this owner would hold a later {@code key_order} of that key behind a
     * dead letter that does not exist, and queue it for the dead-letter table.
     */
    @Test
    void a_key_whose_message_the_charge_brought_to_the_limit_is_not_blocked_when_its_move_finds_the_row_gone() throws Exception {
        var owner = owner();
        enterHandler(owner, "ROW-GONE", 7L, 5L);
        hold(owner, new ShardOwnedStorage.OrderedRow(8L, "ROW-GONE", 6L, new byte[0], 0));
        var database = new ChargingConnection(ShardOwnedSchema.ORDERED_TABLE, Map.of(7L, MAX_ATTEMPTS), Set.of(7L));

        var charge = owner.chargeKeysInHandlerBeforeRelease(database.connection(), OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER);

        assertThat(charge.charged()).as("premise: the message was charged to the limit").isEqualTo(1);
        assertThat(charge.deadLetteredKeys()).as("premise: the move found the row gone and dead-lettered nothing").isEmpty();
        assertThat(database.moved).as("premise: nothing was moved").isEmpty();
        assertThat(owner.owesReleaseCharge(OrderedShardOwner.ReleaseChargeScope.EVERY_KEY_IN_HANDLER)).as("premise: the move was made").isFalse();
        assertThat(deadLetterBlocks(owner)).as("no dead letter, so the key is not blocked").isEmpty();
        assertThat(readyByKey(owner).get("ROW-GONE")).as("the message held above it is kept for dispatch").containsOnlyKeys(6L);
        assertThat(pendingPoisonSeqs(owner)).as("nothing is queued for the dead-letter table").isEmpty();
    }

    // DataSource's own loader, not the thread's context loader: a proxy class must be defined in a loader that sees
    // its interface, which the context loader need not.
    @SuppressWarnings("PMD.UseProperClassLoader")
    private static OrderedShardOwner owner() {
        var settings = ShardOwnerSettings.builder().build();
        // Nothing here reaches the DataSource: every write goes through the connection each test passes.
        var noDatabase = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                                                             (proxy, method, args) -> {
                                                                 throw new UnsupportedOperationException(method.getName());
                                                             });
        return new OrderedShardOwner(new ShardOwnedStorage(noDatabase, (short) 1), 3, 1L, settings, (id, key, payload, type) -> { },
                                     new ShardOwnerMetrics(), RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS), "instance-under-test",
                                     new ShardWakeup(), HandlerDispatch.inline(), false);
    }

    /**
     * Put {@code key} in a handler with message {@code seq} at {@code keyOrder}, as dispatch does. Set directly: reaching it
     * through dispatch would need a database read.
     */
    private static void enterHandler(OrderedShardOwner owner, String key, long seq, long keyOrder) throws Exception {
        synchronized (field(owner, "stateLock")) {
            keysInFlight(owner).put(key, new OrderedShardOwner.InHandler(seq, keyOrder, System.nanoTime()));
        }
    }

    /**
     * Hold {@code row} for dispatch, as a read does for a key whose earlier message is in its handler.
     */
    private static void hold(OrderedShardOwner owner, ShardOwnedStorage.OrderedRow row) throws Exception {
        synchronized (field(owner, "stateLock")) {
            readyByKey(owner).computeIfAbsent(row.key(), key -> new TreeMap<>()).put(row.keyOrder(), row);
        }
    }

    /**
     * Hand {@code row} to the owner as its cursor read or its sweep does.
     */
    private static void accept(OrderedShardOwner owner, ShardOwnedStorage.OrderedRow row) throws Exception {
        var accept = OrderedShardOwner.class.getDeclaredMethod("accept", ShardOwnedStorage.OrderedRow.class);
        accept.setAccessible(true);
        accept.invoke(owner, row);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OrderedShardOwner.InHandler> keysInFlight(OrderedShardOwner owner) throws ReflectiveOperationException {
        return (Map<String, OrderedShardOwner.InHandler>) field(owner, "keysInFlight");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, NavigableMap<Long, ShardOwnedStorage.OrderedRow>> readyByKey(OrderedShardOwner owner) throws ReflectiveOperationException {
        return (Map<String, NavigableMap<Long, ShardOwnedStorage.OrderedRow>>) field(owner, "readyByKey");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> deadLetterBlocks(OrderedShardOwner owner) throws ReflectiveOperationException {
        return (Map<String, Long>) field(owner, "deadLetterBlocks");
    }

    private static List<Long> pendingPoisonSeqs(OrderedShardOwner owner) throws ReflectiveOperationException {
        var seqs = new ArrayList<Long>();
        for (var candidate : (List<?>) field(owner, "pendingPoison")) {
            var seq = candidate.getClass().getDeclaredMethod("seq");
            seq.setAccessible(true);
            seqs.add((Long) seq.invoke(candidate));
        }
        return seqs;
    }

    private static Object field(OrderedShardOwner owner, String name) throws ReflectiveOperationException {
        var field = OrderedShardOwner.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
