/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import io.micrometer.core.instrument.*;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.*;

public final class CdcAvailability {

    public enum State {
        ACTIVE,
        INACTIVE,
        FAILED
    }

    private final AtomicReference<State>  state              = new AtomicReference<>(State.INACTIVE);
    private final AtomicReference<String> slotName           = new AtomicReference<>(null);
    private final AtomicReference<String> reason             = new AtomicReference<>(null);
    private final AtomicLong              lastChangedEpochMs = new AtomicLong(0);
    private final AtomicLong              fallbackCount      = new AtomicLong(0);

    private final Counter fallbackCounter;
    private final Counter startFailuresCounter;
    private final MeterRegistry meterRegistry;

    public CdcAvailability() {
        this(Optional.empty());
    }

    public CdcAvailability(Optional<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry.orElse(null);
        if (this.meterRegistry != null) {
            Gauge.builder("essentials.cdc.active", state, s -> s.get() == State.ACTIVE ? 1.0 : 0.0)
                 .register(this.meterRegistry);
            fallbackCounter = Counter.builder("essentials.cdc.fallback_total").register(this.meterRegistry);
            startFailuresCounter = Counter.builder("essentials.cdc.start_failures_total").register(this.meterRegistry);
        } else {
            fallbackCounter = null;
            startFailuresCounter = null;
        }
    }

    public void active(String slot) {
        set(State.ACTIVE, slot, null);
    }

    public void inactive(String slot, String reason) {
        set(State.INACTIVE, slot, reason);
    }

    public void failed(String slot, String reason) {
        set(State.FAILED, slot, reason);
        if (startFailuresCounter != null) startFailuresCounter.increment();
        incrementStartFailureReason(reason);
    }

    public void fallbackUsed() {
        fallbackCount.incrementAndGet();
        if (fallbackCounter != null) fallbackCounter.increment();
    }

    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }

    public State getState() {
        return state.get();
    }

    public long getFallbackCount() {
        return fallbackCount.get();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                state.get(),
                slotName.get(),
                reason.get(),
                lastChangedEpochMs.get(),
                fallbackCount.get()
        );
    }

    private void set(State newState, String slot, String reason) {
        this.state.set(newState);
        this.slotName.set(slot);
        this.reason.set(reason);
        this.lastChangedEpochMs.set(System.currentTimeMillis());
    }

    private void incrementStartFailureReason(String reason) {
        if (meterRegistry == null) return;
        String tagValue = sanitizeReasonTag(reason);
        Counter.builder("essentials.cdc.start_failures_total")
               .tag("reason", tagValue)
               .register(meterRegistry)
               .increment();
    }

    private static String sanitizeReasonTag(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String normalized = reason.toLowerCase(Locale.ROOT)
                                  .replaceAll("[^a-z0-9]+", "_")
                                  .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) return "unknown";
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    public record Snapshot(State state,
                           String slotName,
                           String reason,
                           long lastChangedEpochMs,
                           long fallbackCount) {
    }
}
