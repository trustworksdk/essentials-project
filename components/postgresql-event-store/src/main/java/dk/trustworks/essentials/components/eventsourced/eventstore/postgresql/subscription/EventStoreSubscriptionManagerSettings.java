/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;


import java.time.Duration;

/**
 * Represents settings for managing EventStore subscriptions.
 * This configuration allows customization of parameters related to how
 * events are polled and how subscription state is maintained during event streaming.
 *
 * @param eventStorePollingBatchSize Specifies the number of events to retrieve in each batch when polling the EventStore.
 * @param eventStorePollingInterval Determines the interval between successive polling attempts to fetch events from the EventStore.
 * @param snapshotResumePointsEvery Specifies the duration after which the subscription's resume points are periodically saved to ensure that
 *                                  a subscription can resume from the last processed event in case of interruptions.
 */
public record EventStoreSubscriptionManagerSettings(int eventStorePollingBatchSize,
                                                    Duration eventStorePollingInterval,
                                                    Duration snapshotResumePointsEvery) {
}
