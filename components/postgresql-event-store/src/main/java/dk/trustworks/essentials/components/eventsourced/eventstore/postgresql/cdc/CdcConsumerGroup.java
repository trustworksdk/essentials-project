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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Represents a consumer group for Change Data Capture (CDC) operations.
 * <p>
 * This class is used to encapsulate the name of a CDC consumer group and provides
 * methods to create and access the group name. Consumer groups are typically
 * utilized to organize and manage consumers that process change events.
 * <p>
 * Instances of this class are immutable.
 */
public final class CdcConsumerGroup {

    private final String name;

    private CdcConsumerGroup(String name) {
        requireNonNull(name, "No name provided");
        this.name = name;
    }

    public static CdcConsumerGroup of(String name) {
        return new CdcConsumerGroup(name);
    }

    public String name() {
        return name;
    }
}
