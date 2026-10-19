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

package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.AccountEvent;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * EF-2 measurement M1 (design note {@code docs/key-partitioned-event-processing.md} §5): a {@code ViewEventProcessor}
 * on the {@code Accounts} stream whose handler does nothing but count. With the handler's cost removed, its rate is the
 * reader's ceiling: subscription, dispatch and the unit of work per event. Started fresh it reads the stream from the
 * beginning, so the existing backlog is the workload. Off unless {@code trading-demo.ef2.noop-view-processor=true}.
 */
@Service
@ConditionalOnProperty(prefix = "trading-demo.ef2", name = "noop-view-processor", havingValue = "true")
public class NoOpAccountsViewProcessor extends ViewEventProcessor {
    static final String NAME = "Ef2NoOpAccountsView";

    private final NoOpProcessorStats stats;

    public NoOpAccountsViewProcessor(ViewEventProcessorDependencies dependencies, NoOpProcessorStats stats) {
        super(dependencies);
        this.stats = requireNonNull(stats, "No stats provided");
    }

    @Override
    public String getProcessorName() {
        return NAME;
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountEvent event) {
        stats.handled(NAME);
    }
}
