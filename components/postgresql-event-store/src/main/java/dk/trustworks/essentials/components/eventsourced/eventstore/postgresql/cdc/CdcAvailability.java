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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.*;

/**
 * The CdcAvailability class is responsible for tracking and reporting the state
 * of Change Data Capture (CDC) availability. It provides methods to update and
 * retrieve the state, as well as mechanisms to track related metrics and statistics.
 * <p>
 * The class maintains an internal state represented by the {@code State} enum,
 * which defines the following possible states:
 * <ul>
 * - {@code ACTIVE}: The CDC is currently active.
 * - {@code INACTIVE}: The CDC is currently inactive.
 * - {@code FAILED}: The CDC has encountered a failure.
 * </ul>
 *
 * It also supports integration with an optional {@code MeterRegistry} for reporting
 * metrics such as fallback occurrences, start failure counts, and active state status.
 * These metrics can be integrated with monitoring systems for observability.
 * <p>
 * Thread-safe operations are ensured using {@code AtomicReference} and {@code AtomicLong}.
 * The following state-related details are tracked:
 * - {@code state}: The current state of the CDC (e.g., ACTIVE, INACTIVE, FAILED).
 * - {@code slotName}: The name of the replication slot being monitored.
 * - {@code reason}: The reason for the current state transition, if applicable.
 * - {@code lastChangedEpochMs}: Timestamp (epoch milliseconds) of the last state change.
 * - {@code fallbackCount}: The number of times the fallback mechanism has been triggered.
 * <p>
 * Additionally, this class supports taking a snapshot of the current state using the
 * {@code Snapshot} record.
 */
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
    private final MeterRegistry meterRegistry;

    /**
     * Multicast sink that replays the latest state to new subscribers and emits on every
     * transition. Used by {@link CdcEventStore} to switch the live source between the CDC bus and
     * classic polling when availability changes mid-subscription. Transitions are the contract —
     * only distinct state changes are published (see {@link #set}).
     */
    private final Sinks.Many<State> stateSink = Sinks.many().replay().latest();
    private final Flux<State>       stateChangesFlux;

    public CdcAvailability() {
        this(Optional.empty());
    }

    public CdcAvailability(Optional<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry.orElse(null);
        if (this.meterRegistry != null) {
            Gauge.builder("essentials.cdc.active", state, s -> s.get() == State.ACTIVE ? 1.0 : 0.0)
                 .register(this.meterRegistry);
            fallbackCounter = Counter.builder("essentials.cdc.fallback_total").register(this.meterRegistry);
            // Pre-register the start-failure counter with a baseline reason tag so the series is
            // visible from startup. Every registration of this meter must share the same tag keys —
            // Prometheus-backed registries reject same-named meters whose tag key sets differ — so
            // the per-reason increments in incrementStartFailureReason() use the same "reason" key.
            // The total is recoverable as sum(essentials.cdc.start_failures_total) across reasons.
            Counter.builder("essentials.cdc.start_failures_total")
                   .tag("reason", "none")
                   .register(this.meterRegistry);
        } else {
            fallbackCounter = null;
        }
        // Seed the replay sink with the initial state so subscribers that connect before any
        // transition (which is the common case) still get a starting value to drive source
        // selection.
        this.stateSink.tryEmitNext(State.INACTIVE);
        this.stateChangesFlux = this.stateSink.asFlux();
    }

    public void active(String slot) {
        set(State.ACTIVE, slot, null);
    }

    public void inactive(String slot, String reason) {
        set(State.INACTIVE, slot, reason);
    }

    public void failed(String slot, String reason) {
        set(State.FAILED, slot, reason);
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

    /**
     * A {@link Flux} that replays the current state on subscription and emits on every subsequent
     * transition. Consumers (currently {@link CdcEventStore}) use it to switch live-subscription
     * sources when CDC becomes unavailable mid-stream without needing to re-subscribe from the
     * top. Multi-subscriber safe — each subscriber receives the current state and all future
     * transitions, regardless of when it subscribes.
     */
    public Flux<State> stateChanges() {
        return stateChangesFlux;
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
        State previous = this.state.getAndSet(newState);
        this.slotName.set(slot);
        this.reason.set(reason);
        this.lastChangedEpochMs.set(System.currentTimeMillis());
        // Only publish on actual transitions. Repeated calls with the same state (e.g. tailer
        // heartbeat marking availability ACTIVE each loop) would otherwise flood subscribers with
        // redundant events; distinctUntilChanged downstream would still filter, but emitting less
        // keeps the replay sink's cached value meaningful and reduces wake-ups.
        if (previous != newState) {
            this.stateSink.tryEmitNext(newState);
        }
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
