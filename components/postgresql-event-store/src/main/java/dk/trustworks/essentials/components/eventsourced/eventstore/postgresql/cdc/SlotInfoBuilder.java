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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.PgReplicationSlots.SlotInfo;

/**
 * Builder for {@link SlotInfo}, obtained from {@link SlotInfo#builder()}.
 * <p>
 * {@code SlotInfo} mirrors a {@code pg_replication_slots} row one-for-one, and PostgreSQL keeps adding columns to that
 * view — {@code conflicting}, {@code invalidation_reason}, {@code failover} and {@code synced} are all recent
 * additions. Every value except {@code slotName} is therefore left at a null/false default rather than being required:
 * a row read from an older server legitimately has no value for the newer columns, and a test fixture rarely cares
 * about more than two or three of them.
 */
public final class SlotInfoBuilder {
    private String  slotName;
    private String  slotType;
    private String  plugin;
    private String  database;
    private Integer activePid;
    private boolean temporary;
    private String  restartLsn;
    private String  confirmedFlushLsn;
    private String  walStatus;
    private Long    safeWalSize;
    private String  inactiveSince;
    private String  conflicting;
    private String  invalidationReason;
    private Boolean failover;
    private Boolean synced;

    /**
     * @param slotName the slot name
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setSlotName(String slotName) {
        this.slotName = slotName;
        return this;
    }

    /**
     * @param slotType {@code logical} or {@code physical} — {@link SlotInfo#isLogical()} reads this
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setSlotType(String slotType) {
        this.slotType = slotType;
        return this;
    }

    /**
     * @param plugin the output plugin backing a logical slot
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setPlugin(String plugin) {
        this.plugin = plugin;
        return this;
    }

    /**
     * @param database the database the slot belongs to
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setDatabase(String database) {
        this.database = database;
        return this;
    }

    /**
     * @param activePid the pid holding the slot, or {@code null} when inactive — {@link SlotInfo#isActive()} reads this
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setActivePid(Integer activePid) {
        this.activePid = activePid;
        return this;
    }

    /**
     * @param temporary whether the slot is temporary. Defaults to {@code false}
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setTemporary(boolean temporary) {
        this.temporary = temporary;
        return this;
    }

    /**
     * @param restartLsn the slot's restart LSN
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setRestartLsn(String restartLsn) {
        this.restartLsn = restartLsn;
        return this;
    }

    /**
     * @param confirmedFlushLsn the slot's confirmed flush LSN
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setConfirmedFlushLsn(String confirmedFlushLsn) {
        this.confirmedFlushLsn = confirmedFlushLsn;
        return this;
    }

    /**
     * @param walStatus {@code reserved}, {@code extended}, {@code unreserved} or {@code lost}
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setWalStatus(String walStatus) {
        this.walStatus = walStatus;
        return this;
    }

    /**
     * @param safeWalSize bytes of WAL that can still be written before the slot risks invalidation
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setSafeWalSize(Long safeWalSize) {
        this.safeWalSize = safeWalSize;
        return this;
    }

    /**
     * @param inactiveSince when the slot went inactive, or {@code null}
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setInactiveSince(String inactiveSince) {
        this.inactiveSince = inactiveSince;
        return this;
    }

    /**
     * @param conflicting whether the slot conflicts with recovery
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setConflicting(String conflicting) {
        this.conflicting = conflicting;
        return this;
    }

    /**
     * @param invalidationReason why the slot was invalidated, or {@code null}
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setInvalidationReason(String invalidationReason) {
        this.invalidationReason = invalidationReason;
        return this;
    }

    /**
     * @param failover whether the slot is enabled for failover
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setFailover(Boolean failover) {
        this.failover = failover;
        return this;
    }

    /**
     * @param synced whether the slot was synced from a primary
     * @return this builder instance for fluent chaining
     */
    public SlotInfoBuilder setSynced(Boolean synced) {
        this.synced = synced;
        return this;
    }

    /**
     * Builds the slot info.
     *
     * @return the slot info
     */
    @SuppressWarnings("removal")
    public SlotInfo build() {
        return new SlotInfo(slotName,
                            slotType,
                            plugin,
                            database,
                            activePid,
                            temporary,
                            restartLsn,
                            confirmedFlushLsn,
                            walStatus,
                            safeWalSize,
                            inactiveSince,
                            conflicting,
                            invalidationReason,
                            failover,
                            synced);
    }
}
