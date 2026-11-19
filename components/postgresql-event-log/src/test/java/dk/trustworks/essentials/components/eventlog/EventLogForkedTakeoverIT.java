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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-JVM takeovers with forked JVMs: each owner is a {@link LeaseOwnerProcess} child on the
 * same classpath; a writer in this JVM appends about 50 events a second throughout. Scenarios: a crash (SIGKILL), a
 * pause (SIGSTOP for twice the lease, then SIGCONT), a graceful stop, and repeated connection loss. Opt-in, like the
 * other forked-JVM checks: {@code -Dspike.run=true} (each scenario starts two JVMs and waits out leases).
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class EventLogForkedTakeoverIT extends AbstractLeaseIT {
    private final List<LeaseOwnerProcess.Line> lines       = new CopyOnWriteArrayList<>();
    private final List<Process>                children    = new CopyOnWriteArrayList<>();
    private final AtomicBoolean                writing     = new AtomicBoolean(true);
    private final AtomicLong                   lastWritten = new AtomicLong();
    private Thread                             writer;

    @BeforeEach
    void startWriter() throws SQLException {
        var writerLog = eventLog("writer");
        createView(LeaseOwnerProcess.VIEW);
        installProgressLog();
        writer = Thread.ofVirtual().start(() -> {
            while (writing.get()) {
                try {
                    var orders = append(writerLog, "order-" + (lastWritten.get() % 7), 1);
                    lastWritten.set(orders.getLast());
                    Thread.sleep(20);
                } catch (Exception e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopChildren() throws Exception {
        writing.set(false);
        writer.join();
        for (var child : children) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private Process child(String nodeName) throws Exception {
        var process = LeaseOwnerProcess.start(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), nodeName, lines);
        children.add(process);
        return process;
    }

    /** @return the fence {@code subscriber} was claimed with by the {@code n}th claim line, waiting for it */
    private long awaitClaim(String subscriber, int n) {
        awaitTrue(() -> claims(subscriber).size() >= n);
        return claims(subscriber).get(n - 1);
    }

    private List<Long> claims(String subscriber) {
        return lines.stream().filter(l -> l.kind().equals("C") && l.fields()[1].equals(subscriber)).map(l -> Long.parseLong(l.fields()[2])).toList();
    }

    private List<LeaseOwnerProcess.Line> handled(long fence) {
        return lines.stream().filter(l -> l.kind().equals("H") && l.fields()[2].equals(Long.toString(fence))).toList();
    }

    private void awaitBothClaimed(int n) {
        awaitClaim(LeaseOwnerProcess.PERIODIC, n);
        awaitClaim(LeaseOwnerProcess.PAGES, n);
    }

    /** The view equals the reference fold and every event reached the periodic subscription at least once */
    private void assertFoldEqual() throws Exception {
        writing.set(false);
        writer.join();
        var last = lastWritten.get();
        awaitTrue(() -> {
            try {
                return queryLong("SELECT coalesce(max(global_order), 0) FROM " + LeaseOwnerProcess.VIEW) >= last;
            } catch (SQLException e) {
                return false;
            }
        });
        assertThat(viewDefects(LeaseOwnerProcess.VIEW, last)).as("view rows duplicated or missing").isZero();
        awaitTrue(() -> handledOrders().size() >= last);
        assertThat(handledOrders()).hasSize((int) last);
    }

    private Set<Long> handledOrders() {
        var orders = new TreeSet<Long>();
        lines.stream().filter(l -> l.kind().equals("H")).forEach(l -> orders.add(Long.parseLong(l.fields()[1])));
        return orders;
    }

    /** Resume-point writes under {@code oldFence} that committed after the first write under {@code newFence} */
    private void assertNoLateWrites(long oldFence, long newFence) throws SQLException {
        for (var subscriber : List.of(LeaseOwnerProcess.PERIODIC, LeaseOwnerProcess.PAGES)) {
            assertThat(resumeWritesAfter(subscriber, oldFence, newFence)).as("%s: writes by the old owner after the new owner's first", subscriber).isEmpty();
        }
    }

    private static void signal(Process process, String signal) throws Exception {
        var kill = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).inheritIO().start();
        assertThat(kill.waitFor()).isZero();
    }

    @Test
    void crash() throws Exception {
        var a = child("node-a");
        var fenceA = awaitClaim(LeaseOwnerProcess.PERIODIC, 1);
        awaitBothClaimed(1);
        child("node-b");
        awaitTrue(() -> handled(fenceA).size() >= 20);

        var killedAt = System.nanoTime();
        a.destroyForcibly();
        var fenceB = awaitClaim(LeaseOwnerProcess.PERIODIC, 2);
        awaitTrue(() -> !handled(fenceB).isEmpty());
        var takeover = Duration.ofNanos(Long.parseLong(handled(fenceB).getFirst().fields()[3]) - killedAt);
        assertThat(takeover).isLessThanOrEqualTo(LEASE.leaseDuration().plus(LEASE.leaseRenewalInterval()).plusSeconds(1));
        assertFoldEqual();
    }

    @Test
    void pause() throws Exception {
        var a = child("node-a");
        var fenceA = awaitClaim(LeaseOwnerProcess.PERIODIC, 1);
        awaitBothClaimed(1);
        child("node-b");
        awaitTrue(() -> handled(fenceA).size() >= 20);

        signal(a, "STOP");
        var fenceB = awaitClaim(LeaseOwnerProcess.PERIODIC, 2);
        Thread.sleep(LEASE.leaseDuration().multipliedBy(2).toMillis());
        signal(a, "CONT");
        awaitTrue(() -> lines.stream().anyMatch(l -> l.kind().equals("L")));
        assertNoLateWrites(fenceA, fenceB);
        assertFoldEqual();
    }

    @Test
    void graceful_handover() throws Exception {
        var a = child("node-a");
        var fenceA = awaitClaim(LeaseOwnerProcess.PERIODIC, 1);
        awaitBothClaimed(1);
        child("node-b");
        awaitTrue(() -> handled(fenceA).size() >= 20);

        var stoppedAt = System.nanoTime();
        LeaseOwnerProcess.stop(a);
        var lastOfA = Long.parseLong(handled(fenceA).getLast().fields()[1]);
        var fenceB  = awaitClaim(LeaseOwnerProcess.PERIODIC, 2);
        awaitTrue(() -> !handled(fenceB).isEmpty());
        var first = handled(fenceB).getFirst();
        assertThat(Duration.ofNanos(Long.parseLong(first.fields()[3]) - stoppedAt)).isLessThanOrEqualTo(LEASE.leaseRenewalInterval().plusSeconds(1));
        assertThat(Long.parseLong(first.fields()[1])).as("nothing replayed beyond the released resume point").isGreaterThan(lastOfA);
        assertFoldEqual();
    }

    @Test
    void connection_loss() throws Exception {
        child("node-a");
        awaitBothClaimed(1);
        child("node-b");
        var until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'node-a'");
            Thread.sleep(2_000);
        }
        // A takeover may or may not have happened; either way the fold holds
        assertFoldEqual();
    }
}
