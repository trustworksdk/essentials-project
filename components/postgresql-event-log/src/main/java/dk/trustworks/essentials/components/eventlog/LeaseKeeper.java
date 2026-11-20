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

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The lease keeper of one {@link EventLog}: one virtual thread, {@code eventlog-lease}, that runs only while the event
 * log has an exclusive
 * subscription. Every {@link EventLogSettings#leaseRenewalInterval()} it borrows one connection and sends at most two
 * statements: one {@code LEASE_RENEW} for every owned lease and one {@code LEASE_CLAIM} for every standby one (plus a
 * {@code LEASE_CLAIM_NEW} per standby subscription that has never seen its row). Each statement has the query timeout
 * {@link EventLogSettings#leaseStatementTimeout()}. It never touches the reader loop and reads no event table.
 * <p>
 * A renewal is confirmed at the time it was <b>sent</b>: the server's {@code now()} is at or after it, so the
 * delivery thread's local deadline always falls before the server's expiry. A failed pass confirms nothing; the
 * deadline, checked by the delivery thread, then suspends delivery.
 */
final class LeaseKeeper {
    private static final Logger log = LoggerFactory.getLogger(LeaseKeeper.class);

    private final DataSource          dataSource;
    private final EventLogSettings    settings;
    private final EventLogObserver    observer;
    private final Supplier<TestSeams> seams;
    private final String              owner;
    private final Set<Lease>          leases = ConcurrentHashMap.newKeySet();
    private Thread                    thread;
    private volatile boolean          stopped;
    // ---- for tests ----
    /** Renewal statements sent */
    volatile long renewStatements;
    /** Passes run */
    volatile long passes;

    LeaseKeeper(DataSource dataSource, EventLogSettings settings, EventLogObserver observer, String owner, Supplier<TestSeams> seams) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.settings = requireNonNull(settings, "No settings provided");
        this.observer = requireNonNull(observer, "No observer provided");
        this.owner = requireNonNull(owner, "No owner provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /** The {@code lease_owner} of every lease this keeper claims */
    String owner() {
        return owner;
    }

    /** Add a standby lease; its first claim is tried at once */
    synchronized void register(Lease lease) {
        requireNonNull(lease, "No lease provided");
        leases.add(lease);
        if (thread == null) {
            thread = Thread.ofVirtual().name("eventlog-lease").start(this::run);
        } else {
            LockSupport.unpark(thread);
        }
    }

    void unregister(Lease lease) {
        leases.remove(lease);
    }

    /** Stop the thread (it starts again on the next {@link #register}); leases are released by their subscriptions, not here */
    void stop() {
        Thread t;
        synchronized (this) {
            stopped = true;
            t = thread;
        }
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(java.time.Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            stopped = false;
        }
    }

    /** @return whether the keeper's thread is running; for tests */
    synchronized boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    private void run() {
        var interval = settings.leaseRenewalInterval().toNanos();
        try {
            while (!stopped) {
                synchronized (this) {
                    if (leases.isEmpty()) {
                        thread = null;
                        return;
                    }
                }
                var started = System.nanoTime();
                try {
                    pass();
                } catch (RuntimeException e) {
                    // A pass that fails outside its own statement handling must not end the keeper: every exclusive
                    // subscription of this EventLog would then suspend for good
                    log.warn("A lease pass of owner {} failed unexpectedly; the next pass runs as usual", owner, e);
                }
                var left = interval - (System.nanoTime() - started);
                if (left > 0 && !stopped) {
                    LockSupport.parkNanos(this, left);
                }
                if (Thread.interrupted() && stopped) {
                    break;
                }
            }
        } finally {
            // Also on an Error: with `thread` still set, register() would never start another keeper
            synchronized (this) {
                if (thread == Thread.currentThread()) {
                    thread = null;
                }
            }
        }
    }

    /** One pass: renew every owned lease, then claim every standby one, on one borrowed connection */
    void pass() {
        var owned   = leases.stream().filter(Lease::wantsRenewal).toList();
        var standby = leases.stream().filter(Lease::wantsClaim).toList();
        if (owned.isEmpty() && standby.isEmpty()) {
            return;
        }
        passes++;
        var current = seams.get();
        try (var connection = dataSource.getConnection()) {
            if (!owned.isEmpty()) {
                renew(connection, owned, current);
            }
            if (!standby.isEmpty()) {
                claim(connection, standby, current);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            observer.onRenewalFailed(e);
            log.debug("A lease pass of owner {} failed; nothing was confirmed", owner, e);
        }
    }

    private record KeyPair(String subscriber, String aggregateType) {
        static KeyPair of(ProgressStore.Key key) {
            return new KeyPair(key.subscriber(), key.aggregateType());
        }
    }

    private String leaseInterval() {
        return settings.leaseDuration().toMillis() + " milliseconds";
    }

    private int timeoutSeconds() {
        return (int) Math.max(1, settings.leaseStatementTimeout().toSeconds());
    }

    private void renew(Connection connection, List<Lease> owned, TestSeams current) throws Exception {
        var sent  = new ArrayList<Lease>(owned.size());
        var fences = new HashMap<Lease, Long>();
        for (var lease : owned) {
            var fence = lease.fenceValue();
            if (fence != null) {
                sent.add(lease);
                fences.put(lease, fence);
            }
        }
        if (sent.isEmpty()) {
            return;
        }
        var keys = sent.stream().map(Lease::key).toList();
        current.hooks().beforeRenewal(keys);
        var start   = System.nanoTime();
        var renewed = new HashSet<KeyPair>();
        if (current.has(RENEW_PER_LEASE)) {
            for (var lease : sent) {
                renewed.addAll(renewStatement(connection, List.of(lease), fences, current));
            }
        } else {
            renewed.addAll(renewStatement(connection, sent, fences, current));
        }
        var nanos = System.nanoTime() - start;
        current.hooks().afterRenewalBeforeConfirm(keys);
        var confirmedAt = current.has(DEADLINE_FROM_RENEWAL_END) ? System.nanoTime() : start;
        observer.onRenewal(sent.size(), renewed.size(), nanos);
        for (var lease : sent) {
            if (!Objects.equals(lease.fenceValue(), fences.get(lease))) {
                continue;
            }
            if (renewed.contains(KeyPair.of(lease.key()))) {
                lease.renewed(confirmedAt);
            } else if (lease.lost("a renewal of fence " + fences.get(lease) + " matched nothing: the fence or owner changed, or the row is gone")) {
                log.warn("Exclusive subscription {} on {} lost its lease (fence {}): ownership moved", lease.key().subscriber(), lease.key().aggregateType(), fences.get(lease));
                observer.onLeaseLost(lease.key().subscriber(), lease.key().aggregateType(), lease.lostReason());
            }
        }
    }

    private Set<KeyPair> renewStatement(Connection connection, List<Lease> leases, Map<Lease, Long> fences, TestSeams current) throws SQLException {
        var renewed = new HashSet<KeyPair>();
        try (var statement = connection.prepareStatement(current.has(RENEW_WITHOUT_FENCE) ? EventLogStorage.LEASE_RENEW_WITHOUT_FENCE : EventLogStorage.LEASE_RENEW)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setString(1, owner);
            statement.setArray(2, connection.createArrayOf("text", leases.stream().map(l -> l.key().subscriber()).toArray(String[]::new)));
            statement.setArray(3, connection.createArrayOf("text", leases.stream().map(l -> l.key().aggregateType()).toArray(String[]::new)));
            statement.setArray(4, connection.createArrayOf("bigint", leases.stream().map(fences::get).toArray(Long[]::new)));
            statement.setString(5, leaseInterval());
            renewStatements++;
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    renewed.add(new KeyPair(resultSet.getString(1), resultSet.getString(2)));
                }
            }
        }
        return renewed;
    }

    private void claim(Connection connection, List<Lease> standby, TestSeams current) throws Exception {
        var keys = standby.stream().map(Lease::key).toList();
        current.hooks().beforeClaim(keys);
        var byKey = new HashMap<KeyPair, Lease>();
        standby.forEach(lease -> byKey.put(KeyPair.of(lease.key()), lease));
        var start   = System.nanoTime();
        var claimed = new HashSet<KeyPair>();
        try (var statement = connection.prepareStatement(current.has(CLAIM_IGNORES_LIVE_LEASE) ? EventLogStorage.LEASE_CLAIM_IGNORING_LEASE : EventLogStorage.LEASE_CLAIM)) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setArray(1, connection.createArrayOf("text", keys.stream().map(ProgressStore.Key::subscriber).toArray(String[]::new)));
            statement.setArray(2, connection.createArrayOf("text", keys.stream().map(ProgressStore.Key::aggregateType).toArray(String[]::new)));
            statement.setString(3, owner);
            statement.setString(4, leaseInterval());
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var pair  = new KeyPair(resultSet.getString(1), resultSet.getString(2));
                    var lease = byKey.get(pair);
                    var claim = new Lease.Claim(resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5), resultSet.getString(6), resultSet.getString(7), resultSet.getString(8));
                    claimed.add(pair);
                    if (lease != null) {
                        took(connection, lease, claim, start);
                    }
                }
            }
        }
        for (var lease : standby) {
            if (claimed.contains(KeyPair.of(lease.key())) || lease.rowSeen()) {
                continue;
            }
            claimNew(connection, lease, start);
        }
    }

    /** A missing key whose subscription never saw its row - insert it with the lease, or find it created by another
     * node */
    private void claimNew(Connection connection, Lease lease, long start) throws SQLException {
        var row = lease.newRow();
        try (var statement = connection.prepareStatement(row.headTable() == null ? EventLogStorage.LEASE_CLAIM_NEW : EventLogStorage.leaseClaimNew(row.headTable()))) {
            statement.setQueryTimeout(timeoutSeconds());
            statement.setString(1, lease.key().subscriber());
            statement.setString(2, lease.key().aggregateType());
            statement.setString(3, row.consistencyMode());
            statement.setString(4, row.progressModel());
            var p = 5;
            if (row.headTable() == null) {
                statement.setLong(p++, row.initialResumeFrom());
            }
            statement.setString(p++, owner);
            statement.setString(p++, leaseInterval());
            statement.setString(p, row.filterDigest());
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    took(connection, lease, new Lease.Claim(resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3), row.consistencyMode(), row.progressModel(), row.filterDigest()), start);
                } else {
                    // Another node created it and holds it: claim it like any other from now on
                    lease.rowSeen(true);
                }
            }
        }
    }

    private void took(Connection connection, Lease lease, Lease.Claim claim, long start) throws SQLException {
        if (lease.claimed(claim, start)) {
            observer.onLeaseClaimed(lease.key().subscriber(), lease.key().aggregateType(), claim.fence());
            return;
        }
        // Closed while the claim was in flight: hand it back at once instead of letting it expire
        try (var statement = connection.prepareStatement(EventLogStorage.LEASE_RELEASE)) {
            var p = ProgressStore.bindKey(statement, 1, lease.key());
            statement.setLong(p++, claim.fence());
            statement.setString(p, owner);
            statement.executeUpdate();
        }
    }
}
