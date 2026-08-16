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
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * The configuration a {@link CdcDispatcher} runs under: which slot's inbox it drains, how it drains it, and whether it
 * runs at all.
 * <p>
 * {@code deliveryMode} stays a plain enum here rather than becoming a {@link CdcDelivery}: the dispatcher only reads
 * it to decide whether to start — in {@link CdcDeliveryMode#DIRECT} the tailer publishes straight to the bus and the
 * dispatcher stands down. There is no collaborator paired with the choice, so there is nothing for a sealed type to
 * make unrepresentable.
 *
 * @param slotName                the replication slot whose inbox rows this dispatcher drains
 * @param cdcDispatcherProperties poll interval, batch size, query timeout, and the poison/dispatched-row policies
 * @param deliveryMode            INBOX (the dispatcher runs) or DIRECT (it stands down)
 */
public record CdcDispatcherSettings(String slotName,
                                    CdcDispatcherProperties cdcDispatcherProperties,
                                    CdcDeliveryMode deliveryMode) {

    public CdcDispatcherSettings {
        requireNonNull(slotName, "slotName cannot be null");
        PostgresqlUtil.checkIsValidTableOrColumnName(slotName);
        requireNonNull(cdcDispatcherProperties, "cdcDispatcherProperties cannot be null");
        requireNonNull(deliveryMode, "deliveryMode cannot be null");
        requireNonNull(cdcDispatcherProperties.getPollInterval(), "pollInterval cannot be null");
        requireTrue(cdcDispatcherProperties.getBatchSize() >= 1, "batchSize has to be 1 or greater");
        requireNonNull(cdcDispatcherProperties.getPoisonPolicy(), "poisonPolicy cannot be null");
        requireNonNull(cdcDispatcherProperties.getDispatchedRowPolicy(), "dispatchedRowPolicy cannot be null");
    }
}
