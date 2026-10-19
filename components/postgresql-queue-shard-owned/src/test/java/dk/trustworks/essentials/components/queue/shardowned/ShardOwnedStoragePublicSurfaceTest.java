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
import java.sql.Connection;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The parts of {@link ShardOwnedStorage}'s public surface that need no database: its argument guards, and the 0.60.0
 * signatures it keeps, deprecated, for code compiled against that release.
 * <p>
 * The {@link DataSource} and {@link Connection} handed in refuse every call, so a guard that lets a bad argument through
 * to the database, or a deprecated method that still writes, fails the test instead of reaching a statement.
 */
class ShardOwnedStoragePublicSurfaceTest {
    private static final short QUEUE_ID = 1;

    private final ShardOwnedStorage storage = new ShardOwnedStorage(untouchable(DataSource.class), QUEUE_ID);

    @Test
    void releaseFencedLeases_refuses_a_null_lane() {
        assertThatThrownBy(() -> storage.releaseFencedLeases(null, Map.of(3, 7L), "instance-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No lane provided");
    }

    @Test
    void releaseFencedLeases_refuses_null_fences() {
        assertThatThrownBy(() -> storage.releaseFencedLeases("unordered", null, "instance-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No fences provided");
    }

    @Test
    void releaseFencedLeases_refuses_a_null_owner_instead_of_releasing_nothing() {
        assertThatThrownBy(() -> storage.releaseFencedLeases("unordered", Map.of(3, 7L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No owner provided");
        // Also with nothing to release: a null owner is a caller's bug whether or not there is work
        assertThatThrownBy(() -> storage.releaseFencedLeases("unordered", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No owner provided");
    }

    @Test
    void deregisterInstance_for_a_lane_refuses_a_null_lane_instead_of_deleting_the_row_unconditionally() {
        // `l.lane <> NULL` is NULL for every lease row, so NOT EXISTS would always hold and the membership row be deleted
        // whatever the other lane still holds.
        assertThatThrownBy(() -> storage.deregisterInstance("instance-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No lane provided");
    }

    @Test
    void deregisterInstance_for_a_lane_refuses_a_null_instance_id() {
        assertThatThrownBy(() -> storage.deregisterInstance(null, "unordered"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No instanceId provided");
    }

    @Test
    void the_0_60_0_signatures_are_still_there_and_deprecated_for_removal() throws NoSuchMethodException {
        var signatures = List.of(
                ShardOwnedStorage.class.getMethod("releaseLeases", String.class, Collection.class, String.class),
                ShardOwnedStorage.class.getMethod("bumpAttemptsOnTakeover", Connection.class, int.class),
                ShardOwnedStorage.class.getMethod("bumpOrderedAttemptsOnTakeover", Connection.class, int.class));
        for (var method : signatures) {
            var deprecated = method.getAnnotation(Deprecated.class);
            assertThat(deprecated).as(method.toString()).isNotNull();
            assertThat(deprecated.forRemoval()).as(method.toString()).isTrue();
            assertThat(deprecated.since()).as(method.toString()).isEqualTo("0.60.1");
        }
    }

    @Test
    @SuppressWarnings("removal")
    void the_unfenced_unordered_takeover_charge_refuses_without_touching_the_connection() {
        assertThatThrownBy(() -> storage.bumpAttemptsOnTakeover(untouchable(Connection.class), 3))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("bumpAttemptsOnTakeover(Connection, int, int, int, String, long)");
    }

    @Test
    @SuppressWarnings("removal")
    void the_unfenced_ordered_takeover_charge_refuses_without_touching_the_connection() {
        assertThatThrownBy(() -> storage.bumpOrderedAttemptsOnTakeover(untouchable(Connection.class), 3))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("bumpOrderedAttemptsOnTakeover(Connection, int, int, String, long)");
    }

    /**
     * A proxy that fails the test on any call but the identity methods.
     */
    private static <T> T untouchable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(ShardOwnedStoragePublicSurfaceTest.class.getClassLoader(), new Class<?>[]{type},
                                                (proxy, method, args) -> switch (method.getName()) {
                                                    case "hashCode" -> System.identityHashCode(proxy);
                                                    case "equals" -> proxy == args[0];
                                                    case "toString" -> "untouchable " + type.getSimpleName();
                                                    default -> throw new AssertionError(type.getSimpleName() + "." + method.getName()
                                                                                                + " called; the argument should have been refused first");
                                                }));
    }
}
