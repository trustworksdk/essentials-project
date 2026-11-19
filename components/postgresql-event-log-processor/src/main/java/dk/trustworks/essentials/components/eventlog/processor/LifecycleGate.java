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
package dk.trustworks.essentials.components.eventlog.processor;

import java.util.concurrent.CompletableFuture;

/**
 * Runs a processor's lifecycle transitions - start, stop, reset, rebuild - one at a time, the way foundation's
 * {@code MultiTableChangeListener} keeps its monitor out of a stop: the monitor is held only to claim and release the
 * gate, never while a transition runs. A stop joins the subscription's delivery thread (for up to 30 seconds); a handler
 * on that thread that calls back into the processor ({@code reactors()}, {@code projections()}, its status) must not
 * find the processor's monitor held by that stop. A second transition waits for the first outside the monitor, so a
 * start never subscribes while the previous subscription is still being stopped.
 * <p>
 * Not reentrant: code inside a transition calls the private, ungated variants of the processor's own methods.
 */
final class LifecycleGate {
    /** The transition in progress, or {@code null}. Guarded by {@code this} */
    private CompletableFuture<Void> inProgress;

    /** A claimed transition; closing it lets the next one run */
    interface Claim extends AutoCloseable {
        @Override
        void close();
    }

    /** Wait, outside the monitor, until no other transition runs, then claim the gate */
    Claim enter() {
        var mine = new CompletableFuture<Void>();
        while (true) {
            CompletableFuture<Void> other;
            synchronized (this) {
                other = inProgress;
                if (other == null) {
                    inProgress = mine;
                    break;
                }
            }
            other.join();
        }
        return () -> {
            synchronized (this) {
                inProgress = null;
            }
            mine.complete(null);
        };
    }
}
