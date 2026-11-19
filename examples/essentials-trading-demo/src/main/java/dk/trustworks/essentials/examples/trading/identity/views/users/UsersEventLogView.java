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

package dk.trustworks.essentials.examples.trading.identity.views.users;

import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.config.*;
import dk.trustworks.essentials.examples.trading.identity.events.*;
import dk.trustworks.essentials.examples.trading.identity.types.IdentityAggregateTypes;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link UsersProjection} on the event-log engine: an {@code EventLogViewProcessor} that hands each
 * event to the projection with the handle of the engine's page transaction, so the row commits together with the
 * subscription's progress. Same processor name as {@link UsersClassicView}.
 */
@EventLogEngine
@Service
public class UsersEventLogView extends TradingDemoEventLogViewProcessor {
    private final UsersProjection projection;

    public UsersEventLogView(EventLogProcessorDependencies dependencies,
                             EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                             UsersProjection projection) {
        super("UsersProjection", List.of(IdentityAggregateTypes.USERS), dependencies, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    /** Empties the view in the rebuild's transaction, as {@code onSubscriptionsReset} does on the classic engine */
    @Override
    protected void onRebuild(ProjectionContext context) {
        projection.truncate(pageHandle());
    }

    @MessageHandler
    void handle(UserRegistered event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(UserLocked event) {
        projection.on(pageHandle(), event);
    }

    @MessageHandler
    void handle(UserUnlocked event) {
        projection.on(pageHandle(), event);
    }
}
