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
package dk.trustworks.essentials.examples.trading.config;

import java.util.concurrent.CompletableFuture;

/**
 * The demo's copy of the integration module's package-private {@code LifecycleGate}: runs a processor's start and stop
 * one at a time without holding a monitor while a stop joins the forwarder's delivery thread (for up to 30 seconds).
 * The monitor is held only to claim and release the gate; a second transition waits for the first outside it, so a start
 * never begins forwarding again while the previous stop is still draining. Not reentrant.
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
