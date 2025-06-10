/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.foundation.fencedlock;

import dk.trustworks.essentials.shared.Lifecycle;
import org.jdbi.v3.core.*;
import org.slf4j.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A simple test implementation of {@link FencedLockManager} using PostgreSQL
 * advisory locks.  Each lockName gets its own long-lived Handle,
 * so the lock remains held until we explicitly close it.
 * <p>
 * Can be used in multi-node tests.
 */
public class TestFencedLockManager implements FencedLockManager, Lifecycle {

    private static final Logger log = LoggerFactory.getLogger(TestFencedLockManager.class);

    private final Jdbi   jdbi;
    private final String instanceId = UUID.randomUUID().toString();

    private final ConcurrentMap<LockName, Handle> lockHandles = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<LockName, Future<?>> pending = new ConcurrentHashMap<>();

    public TestFencedLockManager(Jdbi jdbi) {
        this.jdbi = jdbi;
        log.info("TestFencedLockManager [{}] created", instanceId);
    }

    // ---------------- Lifecycle ----------------

    @Override
    public void start() {
        // no-op
    }

    @Override
    public void stop() {
        pending.values().forEach(f -> f.cancel(true));
        pending.clear();
        for (LockName name : new ArrayList<>(lockHandles.keySet())) {
            releaseLock(name);
        }
        executor.shutdownNow();
    }

    @Override
    public boolean isStarted() {
        return !executor.isShutdown();
    }

    // ---------------- FencedLockManager API ----------------

    @Override
    public Optional<FencedLock> lookupLock(LockName lockName) {
        if (lockHandles.containsKey(lockName)) {
            return Optional.of(new SimpleFencedLock(lockName, instanceId));
        }
        return Optional.empty();
    }

    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName) {
        return tryAcquireLock(lockName, Duration.ZERO);
    }

    @Override
    public Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout) {
        long key = computeKey(lockName);
        long deadline = System.nanoTime() + timeout.toNanos();

        while (timeout.isZero() || System.nanoTime() < deadline) {
            // Open a fresh handle (session)
            Handle handle = jdbi.open();
            boolean got;
            try {
                got = handle.createQuery("SELECT pg_try_advisory_lock(:k)")
                            .bind("k", key)
                            .mapTo(Boolean.class)
                            .one();
            } catch (Exception e) {
                handle.close();
                throw e;
            }

            log.debug("[{}] tryAcquireLock('{}') -> {}", instanceId, lockName, got);
            if (got) {
                // keep this handle open to hold the lock
                lockHandles.put(lockName, handle);
                log.info("[{}] Acquired lock '{}'", instanceId, lockName);
                return Optional.of(new SimpleFencedLock(lockName, instanceId));
            }

            handle.close();

            if (timeout.isZero()) break;
            try { Thread.sleep(50); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }

        log.debug("[{}] Failed to acquire '{}' within {}", instanceId, lockName, timeout);
        return Optional.empty();
    }

    @Override
    public FencedLock acquireLock(LockName lockName) {
        Optional<FencedLock> fl;
        do {
            fl = tryAcquireLock(lockName, Duration.ofMillis(500));
        } while (fl.isEmpty());
        return fl.get();
    }

    @Override
    public boolean isLockAcquired(LockName lockName) {
        boolean held = lockHandles.containsKey(lockName);
        log.debug("[{}] isLockAcquired('{}') -> {}", instanceId, lockName, held);
        return held;
    }

    @Override
    public boolean isLockedByThisLockManagerInstance(LockName lockName) {
        return isLockAcquired(lockName);
    }

    @Override
    public boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName) {
        long key = computeKey(lockName);
        // check pg_locks for any session (pid) other than ours holding it
        return jdbi.withHandle(h ->
                                       h.createQuery(
                                                "SELECT EXISTS (" +
                                                        "  SELECT 1 FROM pg_locks " +
                                                        "  WHERE locktype='advisory' AND objid=:k " +
                                                        "    AND granted AND pid <> pg_backend_pid()" +
                                                        ")"
                                                    )
                                        .bind("k", key)
                                        .mapTo(Boolean.class)
                                        .one()
                              );
    }

    @Override
    public void acquireLockAsync(LockName lockName, LockCallback callback) {
        Future<?> f = executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Optional<FencedLock> fl = tryAcquireLock(lockName);
                if (fl.isPresent()) {
                    callback.lockAcquired(fl.get());
                    return;
                }
                try { Thread.sleep(100); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        });
        pending.put(lockName, f);
    }

    @Override
    public void cancelAsyncLockAcquiring(LockName lockName) {
        Future<?> f = pending.remove(lockName);
        if (f != null) f.cancel(true);
        if (isLockAcquired(lockName)) {
            releaseLock(lockName);
        }
    }

    @Override
    public String getLockManagerInstanceId() {
        return instanceId;
    }

    private void releaseLock(LockName lockName) {
        Handle handle = lockHandles.remove(lockName);
        if (handle != null) {
            long key = computeKey(lockName);
            try {
                handle.createUpdate("SELECT pg_advisory_unlock(:k)")
                      .bind("k", key)
                      .execute();
                log.info("[{}] Released lock '{}'", instanceId, lockName);
            } finally {
                handle.close();
            }
        }
    }

    private long computeKey(LockName lockName) {
        long key = lockName.toString().hashCode();
        log.debug("[{}] computeKey('{}') = {}", instanceId, lockName, key);
        return key;
    }

    private static class SimpleFencedLock implements FencedLock {
        private final LockName name;
        private final String   owner;
        SimpleFencedLock(LockName name, String owner) {
            this.name = name;
            this.owner = owner;
        }
        @Override public LockName getName() { return name; }
        @Override public Long getCurrentToken() { return null; }
        @Override public String getLockedByLockManagerInstanceId() { return owner; }
        @Override public java.time.OffsetDateTime getLockAcquiredTimestamp() { return null; }
        @Override public java.time.OffsetDateTime getLockLastConfirmedTimestamp() { return null; }
        @Override public boolean isLocked() { return true; }
        @Override public boolean isLockedByThisLockManagerInstance() { return true; }
        @Override public void release() { /* no-op for test */ }
        @Override public void registerCallback(LockCallback cb) { /* no-op */ }
    }
}
