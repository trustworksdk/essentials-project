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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link CdcEffectivenessMonitor}, obtained from {@link CdcEffectivenessMonitor#builder()}.
 * <p>
 * Every argument is required; the monitor has no optional collaborators. The builder exists because six positional
 * arguments — four of them collaborator types that are easy to transpose — are past the point where a call site can be
 * read without the signature in front of you.
 */
public final class CdcEffectivenessMonitorBuilder {
    private WalReplicationTailer     tailer;
    private CdcDispatcher            dispatcher;
    private CdcAvailability          availability;
    private CdcDeliveryMode          deliveryMode;
    private CdcHealthCheckProperties config;
    private String                   slotName;

    /**
     * @param tailer the {@link WalReplicationTailer} being monitored. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setTailer(WalReplicationTailer tailer) {
        this.tailer = tailer;
        return this;
    }

    /**
     * @param dispatcher the {@link CdcDispatcher} being monitored. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setDispatcher(CdcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        return this;
    }

    /**
     * @param availability the shared {@link CdcAvailability} tracker this monitor flips on a stuck slot. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setAvailability(CdcAvailability availability) {
        this.availability = availability;
        return this;
    }

    /**
     * @param deliveryMode the CDC delivery mode. Only {@link CdcDeliveryMode#INBOX} is monitored — {@code DIRECT} has no
     *                     dispatcher, so the heuristics do not apply. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setDeliveryMode(CdcDeliveryMode deliveryMode) {
        this.deliveryMode = deliveryMode;
        return this;
    }

    /**
     * @param config the health-check thresholds. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setConfig(CdcHealthCheckProperties config) {
        this.config = config;
        return this;
    }

    /**
     * @param slotName the replication slot name. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEffectivenessMonitorBuilder setSlotName(String slotName) {
        this.slotName = slotName;
        return this;
    }

    /**
     * Builds the monitor.
     *
     * @return the monitor
     */
    @SuppressWarnings("removal")
    public CdcEffectivenessMonitor build() {
        return new CdcEffectivenessMonitor(requireNonNull(tailer, "tailer cannot be null"),
                                           requireNonNull(dispatcher, "dispatcher cannot be null"),
                                           requireNonNull(availability, "availability cannot be null"),
                                           requireNonNull(deliveryMode, "deliveryMode cannot be null"),
                                           requireNonNull(config, "config cannot be null"),
                                           requireNonNull(slotName, "slotName cannot be null"));
    }
}
