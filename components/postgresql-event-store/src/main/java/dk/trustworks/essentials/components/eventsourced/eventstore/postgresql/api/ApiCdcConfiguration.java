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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties;

public record ApiCdcConfiguration(
        boolean enabled,
        String mode,
        String plugin,
        String pgOutputPublicationName,
        String deliveryMode,
        String walParserMode,
        int cdcEventStoreBackfillBatchSize,
        String inboxTableName,
        long inboxTtlDuration,
        String slotName,
        String slotMode,
        String slotGroup,
        String walTailerPollInterval,
        String walTailerPollBackoffInterval,
        String walTailerMaxPollBackoffInterval,
        String walTailerReplicationStatusInterval,
        int dispatcherBatchSize,
        String dispatcherPollInterval,
        String dispatcherPoisonPolicy,
        String dispatcherDispatchedRowPolicy
) {
    public static ApiCdcConfiguration from(boolean enabled,
                                           String slotName,
                                           CdcProperties cdcProperties) {
        return new ApiCdcConfiguration(
                enabled,
                cdcProperties.getMode().name(),
                cdcProperties.getPlugin(),
                cdcProperties.getPgOutput().getPublicationName(),
                cdcProperties.getDeliveryMode().name(),
                cdcProperties.getWalParserMode().name(),
                cdcProperties.getCdcEventStoreBackfillBatchSize(),
                cdcProperties.getInboxTableName(),
                cdcProperties.getInboxTtlDurationDays(),
                slotName,
                cdcProperties.getSlot().getMode().name(),
                cdcProperties.getSlot().getGroup(),
                cdcProperties.getWalReplicationTailer().getPollInterval().toString(),
                cdcProperties.getWalReplicationTailer().getPollBackoffInterval().toString(),
                cdcProperties.getWalReplicationTailer().getMaxPollBackoffInterval().toString(),
                cdcProperties.getWalReplicationTailer().getReplicationStatusInterval().toString(),
                cdcProperties.getCdcDispatcher().getBatchSize(),
                cdcProperties.getCdcDispatcher().getPollInterval().toString(),
                cdcProperties.getCdcDispatcher().getPoisonPolicy().name(),
                cdcProperties.getCdcDispatcher().getDispatchedRowPolicy().name()
        );
    }
}
