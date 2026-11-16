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

import java.util.List;
import java.util.function.*;

/**
 * Test-only bridge from {@code spike.EventLogGateSpikeIT} (another package) to the package-private knobs, counters and
 * hooks the phase-6 gate measurement needs (design phase 6 §7). Not engine code.
 */
public final class GateSpikeAccess {
    private GateSpikeAccess() {
    }

    /** §7.3: {@code false} reads every due type alone (one statement each) */
    public static void flush(EventLog eventLog, boolean on) {
        eventLog.readerLoop().flushForTests = on;
    }

    /** §9 decision 2: {@code true} reads a single due type through the multi-statement flush instead of the combined probe */
    public static void singleTypeFlush(EventLog eventLog, boolean on) {
        eventLog.readerLoop().singleTypeFlushForTests = on;
    }

    /** Batches the notifier sent; 0 without cross-JVM wake-up */
    public static long notifierSends(EventLog eventLog) {
        var notifier = eventLog.notifier();
        return notifier == null ? 0 : notifier.sends.sum();
    }

    /** Notifications the listener received (own instance included); 0 without cross-JVM wake-up */
    public static long listenerNotifications(EventLog eventLog) {
        var listener = eventLog.listener();
        return listener == null ? 0 : listener.notifications.get();
    }

    /**
     * Times the notifier's sends and the listener's receipts through the API: {@code beforeSend} gets each batch on the
     * notifier thread before it is sent, {@code onNotification} each payload on the listener thread
     */
    public static void wakeHooks(EventLog eventLog, Consumer<List<String>> beforeSend, Consumer<String> onNotification) {
        eventLog.wakeSeams(new TestSeams(java.util.Set.of(), new TestSeams.Hooks() {
            @Override
            public void beforeNotifySend(List<String> payloads) {
                beforeSend.accept(payloads);
            }

            @Override
            public void onNotification(String payload) {
                onNotification.accept(payload);
            }
        }, null));
    }
}
