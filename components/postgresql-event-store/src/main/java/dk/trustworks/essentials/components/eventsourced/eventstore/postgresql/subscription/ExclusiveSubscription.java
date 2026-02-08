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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.foundation.fencedlock.LockName;

/**
 * Represents an exclusive subscription that enforces a specific locking mechanism
 * to ensure that only one instance of the subscription can be active at a time.
 * This is achieved through the use of a {@link LockName}, which identifies the
 * lock associated with the exclusive subscription.
 * <p>
 * The {@link LockName} returned by this interface is a contract to identify
 * the lock that governs the exclusivity of the subscription. This ensures
 * consistency and avoids conflicts in multi-threaded or distributed environments.
 */
public interface ExclusiveSubscription {

    LockName lockName();
}
