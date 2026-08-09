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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.views.account_balance;

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates.Accounts;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountDeposited;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountOpened;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountWithdrawn;
import dk.trustworks.essentials.types.Amount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Projector for the {@code banking.account_balance} view slice — events in, read model out. A view slice
 * never produces events (rules/slice-design.md § The four slice kinds).
 * <p>
 * {@link ViewEventProcessor} is the right processor here: asynchronous, replayable, eventually consistent.
 * {@code InTransactionEventProcessor} would only be needed if a balance had to be current the instant the
 * command API returned, and a balance is precisely the kind of figure that does not.
 * <p>
 * Every handler takes {@link OrderedMessage} as its second parameter, because {@code message.getOrder()} is
 * the event's {@code EventOrder} — and comparing it against the row's stored version is what makes the
 * projection idempotent under redelivery and replay.
 */
@Service
public class AccountBalanceProjection extends ViewEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(AccountBalanceProjection.class);

    private final DocumentDbRepository<AccountBalanceView, String> repository;

    public AccountBalanceProjection(ViewEventProcessorDependencies dependencies,
                                    DocumentDbRepository<AccountBalanceView, String> accountBalanceRepository) {
        super(dependencies);
        this.repository = requireNonNull(accountBalanceRepository, "No accountBalanceRepository provided");
    }

    @Override
    public String getProcessorName() {
        return "AccountBalanceProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Accounts.AGGREGATE_TYPE);
    }

    @MessageHandler
    void on(AccountOpened e, OrderedMessage message) {
        var id = e.accountId().toString();
        if (repository.findById(id) != null) {
            return;   // replay of the opening event
        }
        log.debug("===> Projecting AccountOpened for '{}'", id);
        repository.save(new AccountBalanceView(id,
                                               e.accountNumber().toString(),
                                               Amount.ZERO),
                        message.getOrder());
    }

    @MessageHandler
    void on(AccountDeposited e, OrderedMessage message) {
        applyToBalance(e.accountId().toString(), message, current -> current.add(e.depositedAmount()));
    }

    @MessageHandler
    void on(AccountWithdrawn e, OrderedMessage message) {
        applyToBalance(e.accountId().toString(), message, current -> current.subtract(e.withdrawAmount()));
    }

    private void applyToBalance(String accountId,
                                OrderedMessage message,
                                java.util.function.UnaryOperator<Amount> change) {
        var existing = repository.findById(accountId);
        if (existing == null) {
            // AccountOpened is always the first event of the stream, so this can only mean the row was
            // wiped mid-replay. Skipping keeps the projection from inventing an account with no number.
            log.warn("No AccountBalanceView for '{}' - skipping", accountId);
            return;
        }
        if (existing.getVersionValue() >= message.getOrder()) {
            return;   // already applied
        }
        existing.setBalance(change.apply(existing.getBalance()));
        repository.update(existing, message.getOrder());
    }

    /**
     * Rebuild support: wipe the read model so a subscription reset replays cleanly. Called once per
     * {@link AggregateType} this processor subscribes to — there is only one here, so a blanket
     * {@code deleteAll()} is correct. A projection spanning several aggregate types would have to delete
     * only the rows belonging to {@code aggregateType}.
     */
    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        log.info("Resetting AccountBalanceView for '{}' from {}", aggregateType, resubscribeFromAndIncluding);
        repository.deleteAll();
    }
}
