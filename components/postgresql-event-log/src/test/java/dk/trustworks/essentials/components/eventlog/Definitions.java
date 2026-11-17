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
package dk.trustworks.essentials.components.eventlog;

/**
 * Test helper: the definition the removed temporary {@code subscribe}/{@code subscribeExclusive} overloads stood for,
 * built through the phase-8 API (a {@code null} instance name is exclusive; {@code null} mode is {@code FRESH}, {@code null}
 * filter every event type)
 */
public final class Definitions {
    private Definitions() {
    }

    public static SubscriptionDefinition definition(String subscriber, String aggregateType, String instanceName, long startFrom, ConsistencyMode mode,
                                                    EventTypeFilter filter) {
        return SubscriptionDefinition.builder(subscriber, aggregateType)
                                     .ownership(instanceName == null ? Ownership.exclusive() : Ownership.perInstance(instanceName))
                                     .startFrom(StartFrom.globalOrder(startFrom))
                                     .consistency(mode == null ? ConsistencyMode.FRESH : mode)
                                     .eventTypes(filter == null ? EventTypeFilter.all() : filter)
                                     .build();
    }

    /** @return the internal subscription behind a handle the public API returned; for tests that read its state */
    static Subscription internal(SubscriptionHandle handle) {
        return ((DefinedSubscription) handle).subscription();
    }
}
