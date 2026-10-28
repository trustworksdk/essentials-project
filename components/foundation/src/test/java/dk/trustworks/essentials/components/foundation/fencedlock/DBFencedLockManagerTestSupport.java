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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Helpers shared by the {@link DBFencedLockManager} unit tests of this package. Each test class logs through its own
 * {@link DBFencedLockManager} subclass, so the log helpers take the class whose logger they capture.
 */
final class DBFencedLockManagerTestSupport {
    private DBFencedLockManagerTestSupport() {
    }

    /**
     * Captures what is logged through the logger of <code>loggerOf</code> - a lock manager logs through the logger of its
     * own class - until {@link #detachLog(Class, ListAppender)} is called with the same class
     */
    static ListAppender<ILoggingEvent> captureLog(Class<?> loggerOf) {
        var appender = new ListAppender<ILoggingEvent>();
        // Appended to by the lock manager's threads while the test thread reads it
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerOf)).addAppender(appender);
        return appender;
    }

    /**
     * Stops capturing what is logged through the logger of <code>loggerOf</code> into <code>appender</code>
     */
    static void detachLog(Class<?> loggerOf, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(loggerOf)).detachAppender(appender);
    }

    /**
     * A {@link LockCallback} whose <code>lockReleased</code> throws <code>error</code>, and whose <code>lockAcquired</code>
     * does nothing
     */
    static LockCallback lockReleasedFailingWith(Error error) {
        return LockCallback.builder()
                           .onLockAcquired(_lock -> {
                           })
                           .onLockReleased(_lock -> {
                               throw error;
                           })
                           .build();
    }
}
