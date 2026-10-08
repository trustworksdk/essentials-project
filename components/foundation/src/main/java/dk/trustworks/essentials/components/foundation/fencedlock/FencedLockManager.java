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

package dk.trustworks.essentials.components.foundation.fencedlock;

import dk.trustworks.essentials.components.foundation.Lifecycle;

import java.time.Duration;
import java.util.Optional;

/**
 * This library provides a Distributed Locking Manager based of the Fenced Locking concept
 * described <a href="https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html">here</a><br>
 * <br>
 * The {@link FencedLockManager} is responsible for obtaining and managing distributed {@link FencedLock}'s, which are named exclusive locks.<br>
 * Only one {@link FencedLockManager} instance can acquire a {@link FencedLock} at a time.<br>
 * The implementation has been on supporting <b>intra-service</b> (i.e. across different deployed instances of the <b>same</b> service) Lock support through database based implementations (<code>MongoFencedLockManager</code> and <code>PostgresqlFencedLockManager</code>).<br>
 * In a service oriented architecture it's common for all deployed instances of a given service (e.g. a Sales service) to share the same underlying
 * database(s). As long as the different deployed (Sales) services instances can share the same underlying database, then you use the {@link FencedLockManager} concept for handling distributed locks across all deployed (Sales service)
 * instances in the cluster.<br>
 * If you need cross-service lock support, e.g. across instances of different services (such as across Sales, Billing and Shipping services), then you need to use a dedicated distributed locking service such as Zookeeper.<br>
 * <br>
 * To coordinate this properly it's important that each {@link FencedLockManager#getLockManagerInstanceId()}
 * is unique across the cluster.<br>
 * Per default, it uses {@code <hostname>:<pid>} as {@link FencedLockManager#getLockManagerInstanceId()} value (the bare hostname before 0.60.1) -
 * see {@link FencedLockManagerSettings#defaultLockManagerInstanceId()}.<br>
 * <br>
 * <b>Ownership is per lock-manager instance, not per caller or thread.</b> While this {@link FencedLockManager} holds a lock,
 * a second {@link #tryAcquireLock(LockName)}/{@link #acquireLock(LockName)} for the same {@link LockName} - from another
 * component, another thread, or the same thread - succeeds and returns the <b>same</b> {@link FencedLock} instance; it is not
 * refused as "busy". There is no acquire count either: a {@link FencedLock#release()} by <b>any</b> of those callers releases
 * the lock for all of them, after which another node can acquire it while the other callers are still working.<br>
 * So the lock gives mutual exclusion <b>between</b> lock-manager instances (JVMs), never <b>within</b> one. Two callers in one JVM
 * that use the same lock name and must not overlap - e.g. a {@code @Scheduled} run and a manual trigger of the same job - need
 * their own in-process guard (such as an {@code AtomicBoolean}) in front of the lock.
 */
public interface FencedLockManager extends Lifecycle {
    /**
     * Lookup a lock
     *
     * @param lockName the name of the lock
     * @return an {@link Optional} with the locks current state or {@link Optional#empty()} if the lock doesn't exist
     */
    Optional<FencedLock> lookupLock(LockName lockName);

    /**
     * Try to acquire the lock using this Lock Manager instance
     * <p>
     * If this lock manager instance already holds the lock, this returns the <b>same</b> {@link FencedLock} that the earlier
     * caller got - ownership is per lock-manager instance, not per caller or thread, and a {@link FencedLock#release()} by
     * either caller releases it for both. See the class documentation.
     *
     * @param lockName the name of the lock
     * @return An Optional with the {@link FencedLock} IF it was acquired by (or is already held by) the {@link FencedLockManager} on this JVM Node, otherwise it return an empty Optional
     */
    Optional<FencedLock> tryAcquireLock(LockName lockName);

    /**
     * Try to acquire the lock on this JVM Node
     * <p>
     * Like {@link #tryAcquireLock(LockName)}, this returns the already-held {@link FencedLock} at once if this lock manager
     * instance holds the lock - it never waits for another caller in the same JVM to release it.
     *
     * @param lockName the name of the lock
     * @param timeout  timeout for the lock acquiring
     * @return An Optional with the {@link FencedLock} IF it was acquired by the {@link FencedLockManager} on this JVM Node, otherwise it return an empty Optional
     */
    Optional<FencedLock> tryAcquireLock(LockName lockName, Duration timeout);

    /**
     * Acquire the lock on this JVM Node. If the lock already is acquired by another JVM Node,
     * then this method will wait until the lock can be acquired by this JVM node
     * <p>
     * If <b>this</b> lock manager instance already holds the lock, it returns that same {@link FencedLock} immediately instead of
     * waiting for the other caller to release it - ownership is per lock-manager instance, not per caller or thread. See the
     * class documentation.
     *
     * @param lockName the name of the lock
     * @return The {@link FencedLock} when can be acquired by this JVM Node
     */
    FencedLock acquireLock(LockName lockName);

    /**
     * Is the lock acquired
     * @param lockName the name of the lock
     * @return true if the lock is acquired, otherwise false
     */
    boolean isLockAcquired(LockName lockName);

    /**
     * Is the lock already acquired by this JVM node
     *
     * @param lockName the lock name
     * @return true of the lock is acquired by this JVM node
     */
    boolean isLockedByThisLockManagerInstance(LockName lockName);

    /**
     * Is the lock already acquired by another JVM node
     *
     * @param lockName the name of the lock
     * @return true if the lock is acquired by another {@link FencedLockManager} instance (another JVM node), false if it
     * is not locked or is locked by this {@link FencedLockManager} instance
     */
    boolean isLockAcquiredByAnotherLockManagerInstance(LockName lockName);

    /**
     * Asynchronously try to acquire a lock by the given name and call the {@link LockCallback#lockAcquired(FencedLock)}
     * when the lock is acquired<br>
     * To stop the background acquiring process, you need to call {@link #cancelAsyncLockAcquiring(LockName)} with the same
     * lockName
     *
     * @param lockName     the name of the lock
     * @param lockCallback the callback that's notified when a lock is acquired
     */
    void acquireLockAsync(LockName lockName, LockCallback lockCallback);

    /**
     * Cancel a previously started asynchronous lock acquiring background process<br>
     * IF this JVM node had acquired a {@link FencedLock} then this lock
     * will be released AND the {@link LockCallback#lockReleased(FencedLock)} will be called on the {@link LockCallback} instance
     * that was supplied to the {@link #acquireLockAsync(LockName, LockCallback)}<br>
     * Otherwise only the background lock acquiring process will be stopped.
     *
     * @param lockName the name of the lock
     */
    void cancelAsyncLockAcquiring(LockName lockName);

    /**
     * Get the instance id that distinguishes different {@link FencedLockManager} instances from each other<br>
     * For local JVM testing you can assign a unique instance id to allow multiple {@link FencedLockManager}'s to compete for Locks.<br>
     * It must be unique per running lock manager: it is what the lock table, the admin API and the ownership predicates report as
     * the lock holder. The default is {@code <hostname>:<pid>} ({@link FencedLockManagerSettings#defaultLockManagerInstanceId()})
     *
     * @return the instance id
     */
    String getLockManagerInstanceId();
}
