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

import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The progress table (design §2.5, §3.4): create and resume, mode and model stored and checked, the reset-epoch
 * compare-and-set, non-exclusive rows per instance with no lease, and a subscription that repositions on a reset.
 */
class ProgressStoreIT extends AbstractSubscriptionIT {
    private ProgressStore store;

    @BeforeEach
    void createStore() {
        store = new ProgressStore(dataSource);
    }

    @Test
    void start_creates_the_row_once_and_then_resumes_from_it() throws SQLException {
        var key     = new ProgressStore.Key("sub", TYPE, "node-1");
        var created = store.start(key, false, "FRESH", "PERIODIC", 7);
        assertThat(created).isEqualTo(new ProgressStore.Started(7, 0, false));
        assertThat(store.save(key, 42, 0)).isTrue();
        assertThat(store.start(key, false, "FRESH", "PERIODIC", 1)).isEqualTo(new ProgressStore.Started(42, 0, true));
        assertThat(queryLong("SELECT count(*) FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE lease_owner IS NULL AND lease_until IS NULL AND lease_fence IS NULL AND NOT exclusive"))
                .as("phase 2 claims no lease").isEqualTo(1);
    }

    @Test
    void a_changed_mode_or_model_is_updated_and_the_resume_point_kept() throws SQLException {
        var key = new ProgressStore.Key("sub", TYPE, "node-1");
        store.start(key, false, "CONTIGUOUS", "IN_HANDLER_TRANSACTION", 5);
        var restarted = store.start(key, false, "FRESH", "PERIODIC", 1);
        assertThat(restarted.resumeFrom()).isEqualTo(5);
        assertThat(store.read(key)).hasValueSatisfying(p -> {
            assertThat(p.consistencyMode()).isEqualTo("FRESH");
            assertThat(p.progressModel()).isEqualTo("PERIODIC");
        });
    }

    @Test
    void a_save_against_a_moved_reset_epoch_writes_nothing() throws SQLException {
        var key = new ProgressStore.Key("sub", TYPE, "node-1");
        store.start(key, false, "FRESH", "PERIODIC", 1);
        execute("UPDATE " + EventLogSchema.PROGRESS_TABLE + " SET reset_epoch = 3, resume_from = 2");
        assertThat(store.save(key, 99, 0)).isFalse();
        assertThat(store.read(key)).hasValueSatisfying(p -> {
            assertThat(p.resumeFrom()).isEqualTo(2);
            assertThat(p.resetEpoch()).isEqualTo(3);
        });
    }

    @Test
    void instances_of_a_non_exclusive_subscription_keep_their_own_rows() throws SQLException {
        store.start(new ProgressStore.Key("sub", TYPE, "node-1"), false, "FRESH", "PERIODIC", 1);
        store.start(new ProgressStore.Key("sub", TYPE, "node-2"), false, "FRESH", "PERIODIC", 1);
        assertThat(store.save(new ProgressStore.Key("sub", TYPE, "node-2"), 10, 0)).isTrue();
        assertThat(store.read(new ProgressStore.Key("sub", TYPE, "node-1")).orElseThrow().resumeFrom()).isEqualTo(1);
        assertThat(store.read(new ProgressStore.Key("sub", TYPE, "node-2")).orElseThrow().resumeFrom()).isEqualTo(10);
    }

    @Test
    void a_subscription_repositions_when_a_reset_supersedes_its_save() throws Exception {
        var recorder = new Recorder();
        subscribe("sub", recorder);
        var first = commitAppend("A", 3);
        awaitDelivered(recorder, first, first + 1, first + 2);
        await().atMost(WAIT).until(() -> savedResumeFrom("sub") == first + 3);
        execute("UPDATE " + EventLogSchema.PROGRESS_TABLE + " SET reset_epoch = reset_epoch + 1, resume_from = " + first);
        commitAppend("A", 1);
        await().atMost(Duration.ofSeconds(30))
               .until(() -> recorder.delivered.stream().filter(e -> e.globalOrder() == first).count() == 2);
        assertThat(queryLong("SELECT reset_epoch FROM " + EventLogSchema.PROGRESS_TABLE)).isEqualTo(1);
    }
}
