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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.operations.AppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;

import java.util.Optional;

/**
 * Add this interceptor to the {@link EventStore} if you want {@link EventStoreSubscriptionManager#subscribeToAggregateEventsInTransaction(SubscriberId, AggregateType, Optional, TransactionalPersistedEventHandler)}
 * to receive {@link PersistedEvent}'s as soon as they are appended to the {@link EventStore} (i.e. {@link EventStore#appendToStream(AppendToStream)} is performed)
 * <p>
 * Ordering: this interceptor carries no {@link dk.trustworks.essentials.shared.interceptor.InterceptorOrder}, so it runs at the
 * default order {@code 10}. Interceptors with a lower order run before it (further out in the chain); interceptors with the
 * same order keep their registration order.
 * <p>
 * It publishes the {@link AggregateEventStream} that {@code proceed()} returned. An interceptor nested inside it (order greater
 * than {@code 10}) that transforms events on append, e.g. encrypts them, must therefore return the untransformed (plaintext)
 * events in that stream, or in-transaction subscribers receive the transformed (ciphertext) events.
 */
public class FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream implements EventStoreInterceptor {
    @Override
    public <ID> AggregateEventStream<ID> intercept(AppendToStream<ID> operation, EventStoreInterceptorChain<AppendToStream<ID>, AggregateEventStream<ID>> eventStoreInterceptorChain) {
        var eventsPersisted = eventStoreInterceptorChain.proceed();
        if (eventsPersisted.isNotEmpty()) {
            eventStoreInterceptorChain.eventStore().localEventBus().publish(new PersistedEvents(CommitStage.Flush, eventStoreInterceptorChain.eventStore().getUnitOfWorkFactory().getRequiredUnitOfWork(), eventsPersisted.eventList()));
        }
        return eventsPersisted;
    }
}
