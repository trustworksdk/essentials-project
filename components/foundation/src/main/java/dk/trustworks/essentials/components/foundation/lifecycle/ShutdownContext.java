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

package dk.trustworks.essentials.components.foundation.lifecycle;

import dk.trustworks.essentials.components.foundation.IOExceptionUtil;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import org.slf4j.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The application is shutting down: what is left of the shutdown's time budget, and whether the database has already
 * shown itself to be unreachable.
 * <p>
 * Handed to every {@link ShutdownAware} bean by the {@link DefaultLifecycleManager} before it stops the first
 * {@link dk.trustworks.essentials.shared.Lifecycle} bean. The point is the difference between a database failure while
 * running and one during shutdown. While running, retrying is right - the work matters and the database may come back.
 * During shutdown nobody will use the result: what is left is <i>cleanup</i> (releasing fenced locks and leases,
 * deregistering an instance), and each of those has a backstop that expires it anyway. So during shutdown a database
 * step gets {@link #attemptCleanup(String, CheckedRunnable) one short attempt}, and once one attempt has found the
 * database unreachable every later one is skipped, instead of each waiting out the connection pool's timeout in turn.
 */
public final class ShutdownContext {
    private static final Logger log = LoggerFactory.getLogger(ShutdownContext.class);

    /**
     * The default time budget for stopping every {@link dk.trustworks.essentials.shared.Lifecycle} bean
     */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    /**
     * The longest a single {@link #attemptCleanup(String, CheckedRunnable) cleanup attempt} may take. A reachable database
     * answers a lock release in milliseconds; an unreachable one would otherwise hold the caller for the whole connection
     * timeout (30 s by default with HikariCP)
     */
    public static final Duration CLEANUP_ATTEMPT_TIMEOUT  = Duration.ofSeconds(2);

    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                                                                                                                .daemon()
                                                                                                                .name("essentials-shutdown-watchdog")
                                                                                                                .factory());

    private final Instant                 deadline;
    private final Clock                   clock;
    private final AtomicReference<String> databaseUnreachableReason = new AtomicReference<>();

    private ShutdownContext(Instant deadline, Clock clock) {
        this.deadline = deadline;
        this.clock = clock;
    }

    /**
     * @param shutdownTimeout the time budget for the whole shutdown, starting now
     * @return a new {@link ShutdownContext}
     */
    public static ShutdownContext startingNow(Duration shutdownTimeout) {
        requireNonNull(shutdownTimeout, "No shutdownTimeout provided");
        requireTrue(!shutdownTimeout.isNegative(), "shutdownTimeout must not be negative");
        var clock = Clock.systemUTC();
        return new ShutdownContext(clock.instant().plus(shutdownTimeout), clock);
    }

    /**
     * @return the time left of the shutdown's budget; {@link Duration#ZERO} once it has passed
     */
    public Duration remaining() {
        var remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * @return true once the shutdown's time budget has been used up
     */
    public boolean isDeadlinePassed() {
        return remaining().isZero();
    }

    /**
     * @return true once a cleanup attempt has found the database unreachable
     */
    public boolean isDatabaseUnreachable() {
        return databaseUnreachableReason.get() != null;
    }

    /**
     * @return true when further database cleanup should not be attempted - the database is unreachable or the deadline
     * has passed
     */
    public boolean isCleanupAbandoned() {
        return isDatabaseUnreachable() || isDeadlinePassed();
    }

    /**
     * Record that the database is unreachable, so every later {@link #attemptCleanup(String, CheckedRunnable)} is skipped.
     * Logged once, by whichever caller notices first.
     *
     * @param reason what was being attempted and how it failed
     */
    public void databaseUnreachable(String reason) {
        requireNonNull(reason, "No reason provided");
        if (databaseUnreachableReason.compareAndSet(null, reason)) {
            log.info("Database unreachable during shutdown ({}) - skipping the remaining database cleanup. Fenced locks " +
                             "and leases this instance held expire on their own; other instances take them over after that.",
                     reason);
        }
    }

    /**
     * Run one database cleanup step - releasing a lock, a lease, deregistering - if it is still worth trying, bounded by
     * {@link #CLEANUP_ATTEMPT_TIMEOUT} and by what is left of the shutdown's budget.
     * <p>
     * Never throws. A step that times out, or fails with an IO/connection error, marks the database unreachable, so the
     * remaining steps are skipped. The timeout interrupts the calling thread: the step should block interruptibly (a pooled
     * connection checkout does).
     *
     * @param description what the step does, for the log
     * @param cleanup     the step
     * @return true if the step ran and completed, false if it was skipped, failed or timed out
     */
    public boolean attemptCleanup(String description, CheckedRunnable cleanup) {
        requireNonNull(description, "No description provided");
        requireNonNull(cleanup, "No cleanup provided");
        if (isDatabaseUnreachable()) {
            log.debug("Skipping '{}' - database unreachable during shutdown", description);
            return false;
        }
        if (isDeadlinePassed()) {
            log.debug("Skipping '{}' - shutdown timeout passed", description);
            return false;
        }

        var timeout = CLEANUP_ATTEMPT_TIMEOUT.compareTo(remaining()) < 0 ? CLEANUP_ATTEMPT_TIMEOUT : remaining();
        var thread  = Thread.currentThread();
        // 0 = running, 1 = completed, 2 = interrupted by the watchdog. Whoever moves it off 0 first decides, so the
        // watchdog can never interrupt a thread that has already moved on to something else
        var state = new AtomicInteger(0);
        var interrupter = watchdog.schedule(() -> {
            if (state.compareAndSet(0, 2)) {
                thread.interrupt();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        Throwable failure = null;
        try {
            cleanup.run();
        } catch (Throwable e) {
            failure = e;
        } finally {
            interrupter.cancel(false);
        }
        if (!state.compareAndSet(0, 1)) {
            // Our interrupt, not the caller's - don't leak it into whatever the thread does next
            Thread.interrupted();
            databaseUnreachable(description + " did not complete within " + timeout.toMillis() + " ms");
            return false;
        }
        if (failure == null) {
            return true;
        }
        if (failure instanceof Error error && !(failure instanceof AssertionError)) {
            throw error;
        }
        if (IOExceptionUtil.isIOException(failure)) {
            databaseUnreachable(description + " failed: " + failure.getMessage());
        } else {
            log.warn("Shutdown cleanup '{}' failed", description, failure);
        }
        return false;
    }

    @Override
    public String toString() {
        return "ShutdownContext{" +
                "remaining=" + remaining() +
                ", databaseUnreachable=" + isDatabaseUnreachable() +
                '}';
    }
}
