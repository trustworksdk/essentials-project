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

package dk.trustworks.essentials.examples.perflab;

import dk.trustworks.essentials.examples.perflab.queuedesign.QueueSchemaPrototype;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The question that decides how a per-key cursor could ever be deployed: <b>can a pod claiming through the
 * {@code NOT EXISTS} barrier and a pod claiming through the cursor run against the same table at the same
 * time without breaking per-key ordering?</b>
 *
 * <h2>Why it matters more than the cursor's throughput</h2>
 * A rolling deploy replaces pods one at a time, so for a period both claim styles are live against one shared
 * table. If they cannot coexist, the cursor needs a flag-day migration — every consumer stopped, the key-state
 * table backfilled, everything restarted — which for an intra-service queue means downtime, and which is a far
 * larger obstacle than any of the performance work. If they can, the cursor can be rolled out pod by pod and
 * the migration problem mostly disappears.
 *
 * <h2>Why there is reason to think they can</h2>
 * Both mechanisms read the same physical rows. The barrier blocks a successor while any lower-{@code key_order}
 * row is still present, which includes a row the cursor pod is holding in flight. The corrected cursor claim
 * blocks a key while any of its rows has {@code is_being_delivered = TRUE}, which includes a row the barrier
 * pod is holding. The one piece of state that is <em>not</em> shared is {@code completed_through}: a barrier pod
 * deletes rows without advancing it. That can only leave the cursor stale-low, and "lowest order above the
 * cursor" is gap-tolerant by construction, so a stale-low cursor costs a wasted index probe rather than
 * correctness.
 * <p>
 * That is an argument, not a result — the same kind of argument that turned out to be wrong twice about this
 * design already. Hence the test.
 *
 * <h2>What is asserted</h2>
 * Per key: handlings are strictly increasing in {@code key_order}, never overlap in wall-clock time, and no
 * message is handled twice or lost. Both pods must participate, or the run proves nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueueCursorMixedRolloutIT {

    private static final int      KEY_COUNT        = 8;
    private static final int      MESSAGES_PER_KEY = 25;
    /**
     * Wide enough that a genuine same-key overlap between the two pods is observable rather than a photo
     * finish.
     */
    private static final Duration HANDLER_DURATION = Duration.ofMillis(4);

    // Deliberately NOT annotated @Container — see BackpressureScenarioSmokeIT.
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5-bookworm")
            .withDatabaseName("essentials_lab")
            .withUsername("essentials")
            .withPassword("essentials");

    private String messageTable;
    private String keyStateTable;

    @BeforeAll
    static void startContainer() {
        postgres.start();
    }

    @BeforeEach
    void createSchema() {
        messageTable = "mr_msg";
        keyStateTable = "mr_ks";
        withHandle(handle -> {
            handle.execute("DROP TABLE IF EXISTS " + messageTable);
            handle.execute("DROP TABLE IF EXISTS " + keyStateTable);
            QueueSchemaPrototype.cursorSafeOrderedTableDdl(messageTable, 100).forEach(handle::execute);
            QueueSchemaPrototype.cursorKeyStateDdl(keyStateTable).forEach(handle::execute);
            return null;
        });
    }

    @Test
    void a_barrier_pod_and_a_cursor_pod_can_share_one_table_without_breaking_per_key_ordering() throws Exception {
        var totalMessages = KEY_COUNT * MESSAGES_PER_KEY;
        givenOrderedBacklog();

        var handled  = new ConcurrentLinkedQueue<Handled>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            // Two pods of different vintages against one table: the old one claims through the barrier and
            // acknowledges with a plain delete, knowing nothing about the key-state table; the new one claims
            // through the cursor and maintains it.
            var barrierPod = executor.submit(() -> drain("barrier-pod",
                                                         QueueSchemaPrototype.claimOrderedSql(messageTable, true),
                                                         QueueSchemaPrototype.deleteBatchSql(messageTable),
                                                         handled,
                                                         totalMessages));
            var cursorPod = executor.submit(() -> drain("cursor-pod",
                                                        QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                        QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                        handled,
                                                        totalMessages));
            barrierPod.get(2, TimeUnit.MINUTES);
            cursorPod.get(2, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        assertThat(handled).as("every message must be handled").hasSize(totalMessages);

        var byPod = handled.stream().collect(Collectors.groupingBy(Handled::pod, Collectors.counting()));
        assertThat(byPod.keySet()).as("both pods must claim, or this is not a mixed-version run at all")
                                  .containsExactlyInAnyOrder("barrier-pod", "cursor-pod");
        // Presence is not enough: a run where one pod took 199 of 200 would satisfy the check above while
        // barely exercising the interleaving this test exists to stress. A tenth each is a low bar deliberately
        // - the split is not expected to be even, only genuinely shared.
        var minimumShare = totalMessages / 10;
        byPod.forEach((pod, count) -> assertThat(count)
                .as("%s handled %d of %d - too few to call this an interleaved run", pod, count, totalMessages)
                .isGreaterThanOrEqualTo(minimumShare));

        var duplicates = handled.stream()
                                .collect(Collectors.groupingBy(h -> h.key() + "#" + h.order(), Collectors.counting()))
                                .entrySet().stream()
                                .filter(entry -> entry.getValue() > 1)
                                .map(Map.Entry::getKey)
                                .toList();
        assertThat(duplicates).as("no message may be handled twice").isEmpty();

        assertThat(handled.stream().map(Handled::key).distinct().count()).isEqualTo(KEY_COUNT);
        assertThat(orderingViolations(handled)).as("per-key ordering must hold across both claim styles").isEmpty();
    }

    /**
     * The end of the rollout, and the case that would strand messages if it were got wrong: a <b>fully
     * migrated</b> fleet — no barrier pod left — draining a backlog that an old pod enqueued without cursor
     * rows.
     * <p>
     * Every key here is invisible to a cursor claim on arrival, so without recovery the queue would sit
     * untouched: no error, no dead letter, just messages nobody claims. The pods run reconciliation when a claim
     * comes back empty, which is the proposed production trigger, and that alone must be enough to drain the
     * whole backlog.
     * <p>
     * This is the counterpart to the mixed test above. That one shows old and new pods can coexist; this one
     * shows the fleet still converges after the last old pod is gone, which is where a
     * backfill-before-the-deploy strategy fails — the window it cannot cover is the deploy itself.
     */
    @Test
    void a_fully_migrated_fleet_recovers_messages_an_old_pod_enqueued_without_cursor_rows() throws Exception {
        var totalMessages = KEY_COUNT * MESSAGES_PER_KEY;
        givenOrderedBacklogWithoutCursorRows();

        // Nothing is claimable yet - every key lacks a cursor row.
        var beforeRecovery = withHandle(handle -> handle.createQuery(QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable))
                                                       .bind("queueName", "q")
                                                       .bind("now", OffsetDateTime.now())
                                                       .bind("limit", 10)
                                                       .mapTo(String.class)
                                                       .list());
        assertThat(beforeRecovery).as("the backlog starts invisible to cursor pods").isEmpty();

        var handled  = new ConcurrentLinkedQueue<Handled>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var podA = executor.submit(() -> drain("cursor-pod-a",
                                                   QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                   QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                   handled, totalMessages, true));
            var podB = executor.submit(() -> drain("cursor-pod-b",
                                                   QueueSchemaPrototype.claimOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                   QueueSchemaPrototype.ackOrderedViaSafeCursorSql(messageTable, keyStateTable),
                                                   handled, totalMessages, true));
            podA.get(2, TimeUnit.MINUTES);
            podB.get(2, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        assertThat(handled).as("reconciliation on an empty claim must be enough to drain the whole backlog")
                           .hasSize(totalMessages);
        assertThat(orderingViolations(handled)).as("and per-key ordering must hold throughout recovery").isEmpty();
    }

    /**
     * Negative control: the same mixed-pod run with the <em>uncorrected</em> cursor claim, which must produce
     * violations.
     * <p>
     * Fifteen consecutive green runs of the test above are only meaningful if the detector can fail at all, and
     * an ordering detector that never fires is indistinguishable from one that is broken. The uncorrected claim
     * is a known violator — it releases a key's successor while the predecessor is in flight — so pairing it
     * with a barrier pod must be caught. If this test ever stops finding violations, the assertion above has
     * stopped meaning anything.
     */
    @Test
    void control_the_uncorrected_cursor_claim_does_break_ordering_which_proves_the_detector_fires() throws Exception {
        var totalMessages = KEY_COUNT * MESSAGES_PER_KEY;
        givenOrderedBacklog();

        var handled  = new ConcurrentLinkedQueue<Handled>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var barrierPod = executor.submit(() -> drain("barrier-pod",
                                                         QueueSchemaPrototype.claimOrderedSql(messageTable, true),
                                                         QueueSchemaPrototype.deleteBatchSql(messageTable),
                                                         handled,
                                                         totalMessages));
            var cursorPod = executor.submit(() -> drain("cursor-pod",
                                                        QueueSchemaPrototype.claimOrderedViaCursorSql(messageTable, keyStateTable),
                                                        QueueSchemaPrototype.ackOrderedViaCursorSql(messageTable, keyStateTable),
                                                        handled,
                                                        totalMessages));
            barrierPod.get(2, TimeUnit.MINUTES);
            cursorPod.get(2, TimeUnit.MINUTES);
        } finally {
            executor.shutdownNow();
        }

        var violations = orderingViolations(handled);
        assertThat(violations).as("the uncorrected cursor claim must break per-key ordering against a barrier pod - "
                                         + "if this is empty the detector cannot fire and the mixed-rollout result above is worthless")
                              .isNotEmpty();
    }

    /**
     * One pod's drain loop: claim a single message, simulate handling it, acknowledge. Claiming one at a time
     * keeps the two pods genuinely interleaved instead of one of them sweeping the backlog in a single batch.
     */
    private void drain(String pod, String claimSql, String ackSql, Queue<Handled> handled, int totalMessages) {
        drain(pod, claimSql, ackSql, handled, totalMessages, false);
    }

    private void drain(String pod, String claimSql, String ackSql, Queue<Handled> handled, int totalMessages, boolean reconcileOnEmptyClaim) {
        var deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (handled.size() < totalMessages && System.nanoTime() < deadline) {
            var claimed = withHandle(handle -> handle.createQuery(claimSql)
                                                    .bind("queueName", "q")
                                                    .bind("now", OffsetDateTime.now())
                                                    .bind("limit", 1)
                                                    .mapTo(String.class)
                                                    .list());
            if (claimed.isEmpty()) {
                if (reconcileOnEmptyClaim) {
                    // The proposed production trigger. An empty claim is exactly when it is worth asking whether
                    // a key is invisible for want of a cursor row, and the statement is bounded by key count and
                    // idempotent, so running it here converges without operator involvement.
                    withHandle(handle -> handle.createUpdate(QueueSchemaPrototype.reconcileKeyStateSql(keyStateTable, messageTable))
                                               .bind("queueName", "q")
                                               .execute());
                }
                // The other pod holds every eligible key; yield rather than spin.
                LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
                continue;
            }
            var id    = claimed.getFirst();
            var parts = id.split("#");
            var start = System.nanoTime();
            try {
                Thread.sleep(HANDLER_DURATION.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            var end = System.nanoTime();
            withHandle(handle -> handle.createUpdate(ackSql).bindList("ids", List.of(id)).execute());
            handled.add(new Handled(parts[0], Long.parseLong(parts[1]), pod, start, end));
        }
    }

    /**
     * Every per-key ordering fault in one place, so the mixed-rollout test and its negative control are judged
     * by identical logic: a handling that starts after another for the same key must carry a higher
     * {@code key_order}, and their wall-clock windows must not intersect.
     */
    private static List<String> orderingViolations(Collection<Handled> handled) {
        var violations = new ArrayList<String>();
        handled.stream().collect(Collectors.groupingBy(Handled::key)).forEach((key, forKey) -> {
            var byStart = forKey.stream().sorted(Comparator.comparingLong(Handled::startNanos)).toList();
            for (var i = 1; i < byStart.size(); i++) {
                var previous = byStart.get(i - 1);
                var current  = byStart.get(i);
                if (current.order() <= previous.order()) {
                    violations.add("%s: order %d (%s) handled after order %d (%s)".formatted(
                            key, current.order(), current.pod(), previous.order(), previous.pod()));
                }
                if (current.startNanos() < previous.endNanos()) {
                    violations.add("%s: order %d (%s) overlapped order %d (%s)".formatted(
                            key, current.order(), current.pod(), previous.order(), previous.pod()));
                }
            }
        });
        return violations;
    }

    /**
     * The backlog an old pod leaves behind: messages inserted with no cursor rows seeded.
     */
    private void givenOrderedBacklogWithoutCursorRows() {
        withHandle(handle -> {
            for (var order = 0; order < MESSAGES_PER_KEY; order++) {
                for (var key = 0; key < KEY_COUNT; key++) {
                    handle.createUpdate(QueueSchemaPrototype.insertOrderedSql(messageTable, true))
                          .bind("id", "key-" + key + "#" + order)
                          .bind("queueName", "q")
                          .bind("payload", "{}")
                          .bind("payloadType", "Test")
                          .bind("now", OffsetDateTime.now())
                          .bind("key", "key-" + key)
                          .bind("keyOrder", (long) order)
                          .execute();
                }
            }
            return null;
        });
    }

    private void givenOrderedBacklog() {
        withHandle(handle -> {
            for (var order = 0; order < MESSAGES_PER_KEY; order++) {
                for (var key = 0; key < KEY_COUNT; key++) {
                    handle.createUpdate(QueueSchemaPrototype.insertOrderedSql(messageTable, true))
                          .bind("id", "key-" + key + "#" + order)
                          .bind("queueName", "q")
                          .bind("payload", "{}")
                          .bind("payloadType", "Test")
                          .bind("now", OffsetDateTime.now())
                          .bind("key", "key-" + key)
                          .bind("keyOrder", (long) order)
                          .execute();
                }
            }
            handle.createUpdate(QueueSchemaPrototype.seedKeyStateSql(keyStateTable, messageTable))
                  .bind("queueName", "q")
                  .execute();
            return null;
        });
    }

    /**
     * A fresh connection per operation, so the two pods never share one and behave as separate processes.
     */
    private static <T> T withHandle(org.jdbi.v3.core.HandleCallback<T, RuntimeException> callback) {
        var jdbi = Jdbi.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        return jdbi.withHandle(callback);
    }

    private record Handled(String key, long order, String pod, long startNanos, long endNanos) {
    }
}
