package com.acme.multi.ledger.aggregates;

import com.acme.multi.ledger.events.AccountEvent;
import com.acme.multi.ledger.events.AccountOpened;
import com.acme.multi.ledger.events.EntryPosted;
import com.acme.multi.ledger.types.AccountId;
import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;

public class Account extends AggregateRoot<AccountId, AccountEvent, Account> {
    private boolean opened;

    public Account(AccountId aggregateId) { super(aggregateId); }

    public void post(long amountMinor) {
        if (!opened) {
            throw new IllegalStateException("Account is not open");
        }
        if (amountMinor == 0) {
            throw new IllegalArgumentException("An entry must move money");
        }
        apply(new EntryPosted(aggregateId(), amountMinor));
    }

    @EventHandler
    private void on(AccountOpened e) { opened = true; }

    @EventHandler
    private void on(EntryPosted e) { }
}
