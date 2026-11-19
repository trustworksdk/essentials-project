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

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** One set of permits per database key, sized by the first caller, fair */
class CatchUpPermitsTest {
    @Test
    void one_set_per_database_sized_by_the_first() {
        var key   = "db-" + UUID.randomUUID();
        var first = CatchUpPermits.forDatabase(key, 3);
        assertThat(CatchUpPermits.forDatabase(key, 7)).as("a later size is ignored").isSameAs(first);
        assertThat(first.size()).isEqualTo(3);
        assertThat(CatchUpPermits.forDatabase(key + "-other", 3)).isNotSameAs(first);
        assertThat(CatchUpPermits.unshared(key, 3)).isNotSameAs(first);
    }

    @Test
    void waiters_are_served_in_arrival_order() throws Exception {
        var permits = CatchUpPermits.unshared("fair", 1);
        permits.acquire();
        var order   = new CopyOnWriteArrayList<Integer>();
        var threads = new ArrayList<Thread>();
        for (var i = 0; i < 5; i++) {
            var n = i;
            var t = Thread.ofVirtual().start(() -> {
                try {
                    permits.acquire();
                    order.add(n);
                    permits.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
            // the next waiter queues only after this one does
            while (t.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
        }
        assertThat(permits.inUse()).isEqualTo(1);
        permits.release();
        for (var t : threads) {
            t.join(5_000);
        }
        assertThat(order).containsExactly(0, 1, 2, 3, 4);
        assertThat(permits.inUse()).isZero();
    }
}
