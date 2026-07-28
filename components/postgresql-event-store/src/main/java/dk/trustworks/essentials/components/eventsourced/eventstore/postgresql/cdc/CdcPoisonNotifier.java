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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;

import java.util.List;

/**
 * CdcPoisonNotifier is an interface that defines a mechanism for handling poison events
 * encountered in a Change Data Capture (CDC) system. Poison events typically signify
 * inconsistencies, errors, or gaps in event processing that need to be acted upon.
 * <p>
 * Implementations of this interface can define specific behaviors, such as logging the incident,
 * resetting subscribers, or recording the poison details for analysis.
 */
public interface CdcPoisonNotifier {

    /**
     * Handles a poison event encountered during processing in a Change Data Capture (CDC) system.
     * A poison event indicates a critical issue, such as inconsistencies, errors, or gaps in the
     * processing of data.
     *
     * @param aggregateType Represents the aggregate type that encountered the poison event.
     * @param gaps A list of global event orders identifying gaps or inconsistencies in data processing.
     * @param reason A diagnostic message explaining the cause or nature of the poison event.
     */
    void onPoison(AggregateType aggregateType,
                  List<GlobalEventOrder> gaps,
                  String reason);

    final class NoOpCdcPoisonNotifier implements CdcPoisonNotifier {
        @Override public void onPoison(AggregateType aggregateType, List<GlobalEventOrder> gaps, String reason) {}
    }

}
