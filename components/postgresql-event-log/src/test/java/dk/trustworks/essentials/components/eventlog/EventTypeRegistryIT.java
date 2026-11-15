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

import static org.assertj.core.api.Assertions.*;

/**
 * Decision 2: event types are interned in their own committed transaction, cached only after commit.
 */
class EventTypeRegistryIT extends AbstractEventLogIT {

    @Test
    void concurrent_first_appends_of_a_new_type_from_several_engines_agree_on_one_id() throws Exception {
        var writers = 8;
        var engines = new ArrayList<EventLog>();
        for (var i = 0; i < writers; i++) {
            engines.add(startedEventLog("Orders"));
        }
        var start    = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        try {
            var futures = new ArrayList<Future<AppendResult>>();
            for (var i = 0; i < writers; i++) {
                var engine = engines.get(i);
                var stream = "o" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return appendCommitted(engine, AppendRequest.afterEventOrder("Orders", stream, -1, List.of(event("BrandNew", "{}")))).getFirst();
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).events()).hasSize(1);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(queryLong("SELECT count(*) FROM el_event_types WHERE name = 'BrandNew'")).isEqualTo(1);
        var id = queryLong("SELECT id FROM el_event_types WHERE name = 'BrandNew'");
        for (var engine : engines) {
            assertThat(engine.eventTypes().idOf("BrandNew")).isEqualTo((short) id);
        }
        assertThat(queryLong("SELECT count(DISTINCT event_type) FROM el_orders_events")).isEqualTo(1);
        assertThat(engines.getFirst().loadStream("Orders", "o3")).extracting(StoredEvent::eventType).containsExactly("BrandNew");
    }

    @Test
    void a_rolled_back_append_leaves_the_registration_committed_and_the_cache_consistent() throws Exception {
        var eventLog = startedEventLog("Orders");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            eventLog.append(connection, AppendRequest.afterEventOrder("Orders", "o1", -1, List.of(event("RolledBackType", "{}"))));
            connection.rollback();
        }
        assertThat(eventLog.loadStream("Orders", "o1")).isEmpty();
        var id = eventLog.eventTypes().idOf("RolledBackType");
        assertThat(queryLong("SELECT id FROM el_event_types WHERE name = 'RolledBackType'")).as("the cached id is the committed row's").isEqualTo(id);

        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, List.of(event("RolledBackType", "{}"))));
        assertThat(startedEventLog("Orders").loadStream("Orders", "o1")).extracting(StoredEvent::eventType).containsExactly("RolledBackType");
    }

    @Test
    void a_known_name_found_after_a_restart_draws_no_new_identity_value() throws Exception {
        var eventLog = startedEventLog("Orders");
        var id       = eventLog.eventTypes().idOf("Known");
        for (var i = 0; i < 3; i++) {
            assertThat(new EventTypeRegistry(dataSource).idOf("Known")).isEqualTo(id);
        }
        var next = new EventTypeRegistry(dataSource).idOf("Other");
        assertThat(next).as("no id was burned by the restarts").isEqualTo((short) (id + 1));
    }
}
