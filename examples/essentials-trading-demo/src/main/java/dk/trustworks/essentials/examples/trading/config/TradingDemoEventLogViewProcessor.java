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
package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.eventlog.integration.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import org.jdbi.v3.core.Handle;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The new-engine twin of {@link TradingDemoViewEventProcessor}: a view on the event log's {@link EventLogViewProcessor}
 * ({@code CONTIGUOUS}, one instance at a time). Its {@code @MessageHandler} methods run inside the engine's page
 * transaction, which the starter opens through Spring ({@code SpringPageTransactions}) when the application has a
 * transaction manager, as the demo does.
 * <p>
 * {@link #pageHandle()} is how a handler writes: the demo's unit of work factory is Spring transaction aware, so
 * {@code getRequiredUnitOfWork()} joins the page's Spring transaction and its JDBI handle uses the page's connection.
 * The write commits with the page, together with the subscription's progress (proven by
 * {@code UnclearedDepositsEventLogViewIT}). Outside a page there is no active transaction and the call fails.
 * <p>
 * Keep the processor name the same as the classic view's, so both engines' admin views and logs name it alike.
 */
public abstract class TradingDemoEventLogViewProcessor extends EventLogViewProcessor {
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;

    protected TradingDemoEventLogViewProcessor(String name,
                                               List<AggregateType> aggregateTypes,
                                               EventLogProcessorDependencies dependencies,
                                               EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        super(name, aggregateTypes.stream().map(AggregateType::toString).toList(), dependencies);
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
    }

    /**
     * @return the JDBI handle of the page transaction the current handler runs in
     */
    protected Handle pageHandle() {
        return unitOfWorkFactory.getRequiredUnitOfWork().handle();
    }
}
