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

package dk.trustworks.essentials.shared.concurrent;

import dk.trustworks.essentials.shared.FailFast;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for a {@link ThreadFactory} to allow setting thread names, auto increment thread numbers, etc.
 */
public final class ThreadFactoryBuilder {
    private String                   nameFormat;
    private Optional<ThreadFactory>  delegateThreadFactory = Optional.empty();
    private Integer                  priority;
    private boolean                  daemon;
    private UncaughtExceptionHandler uncaughtExceptionHandler;

    /**
     * Create a new builder
     * @return the new builder
     */
    public static ThreadFactoryBuilder builder() {
        return new ThreadFactoryBuilder();
    }

    /**
     * Create the {@link ThreadFactory} based on the builder settings
     * @return the {@link ThreadFactory}
     */
    public ThreadFactory build() {
        return buildThreadFactory(this);
    }

    private static ThreadFactory buildThreadFactory(ThreadFactoryBuilder threadFactoryBuilder) {
        // Change to local variables to break link back to the builder
        var nameFormat               = threadFactoryBuilder.nameFormat;
        var daemon                   = threadFactoryBuilder.daemon;
        var priority                 = threadFactoryBuilder.priority;
        var uncaughtExceptionHandler = threadFactoryBuilder.uncaughtExceptionHandler;
        var actualThreadFactory      = threadFactoryBuilder.delegateThreadFactory.orElse(Executors.defaultThreadFactory());
        var threadCounter            = new AtomicLong();

        return r -> {
            var newThread = actualThreadFactory.newThread(r);
            newThread.setName(String.format(nameFormat, threadCounter.incrementAndGet()));
            newThread.setDaemon(daemon);
            if (priority != null) {
                newThread.setPriority(priority);
            }
            if (uncaughtExceptionHandler != null) {
                newThread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            }
            return newThread;
        };
    }

    /**
     * Set the name format for generated thread names. The format supports
     * using %d as the placeholder for the thread number (every thread created
     * by the resulting {@link ThreadFactory} will automatically receive
     * sequential increasing thread number).<br>
     * Example: "polling-process-%d" or "%d-connection-pool"
     *
     * @param nameFormat the name format
     * @return this {@link ThreadFactoryBuilder} instance
     */
    public ThreadFactoryBuilder nameFormat(String nameFormat) {
        this.nameFormat = requireNonNull(nameFormat, "No nameFormat was provided");
        return this;
    }

    /**
     * Set the {@link ThreadFactory} that will generate new {@link Thread}'s.
     * If none is specified the {@link Executors#defaultThreadFactory()}
     * will be used
     *
     * @param delegateThreadFactory the underlying {@link ThreadFactory}
     * @return this {@link ThreadFactoryBuilder} instance
     */
    public ThreadFactoryBuilder delegateThreadFactory(ThreadFactory delegateThreadFactory) {
        this.delegateThreadFactory = Optional.ofNullable(delegateThreadFactory);
        return this;
    }

    /**
     * Set the priority of the {@link Thread}'s generated by the resulting {@link ThreadFactory}
     *
     * @param priority the priority of the {@link Thread}'s created. Min value is {@link Thread#MIN_PRIORITY}
     *                 and max value is {@link Thread#MAX_PRIORITY}
     * @return this {@link ThreadFactoryBuilder} instance
     */
    public ThreadFactoryBuilder priority(int priority) {
        FailFast.requireTrue(priority >= Thread.MIN_PRIORITY, "priority must be equal or larger to " + Thread.MIN_PRIORITY);
        FailFast.requireTrue(priority <= Thread.MAX_PRIORITY, "priority must be equal or less to " + Thread.MAX_PRIORITY);
        this.priority = priority;
        return this;
    }

    /**
     * Set if the {@link Thread}'s created by the resulting {@link ThreadFactory} are daemon threads
     *
     * @param daemon should the {@link Thread}'s created be daemon threads
     * @return this {@link ThreadFactoryBuilder} instance
     */
    public ThreadFactoryBuilder daemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    /**
     * Set the {@link UncaughtExceptionHandler} for the {@link Thread}'s created by the resulting {@link ThreadFactory} are daemon threads
     *
     * @param uncaughtExceptionHandler the {@link UncaughtExceptionHandler} for the {@link Thread}'s created
     * @return this {@link ThreadFactoryBuilder} instance
     */
    public ThreadFactoryBuilder uncaughtExceptionHandler(UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
        return this;
    }
}
