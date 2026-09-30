package com.acme.multi.ledger.use_cases.open_account;

import com.acme.multi.ledger.events.AccountEvent;
import com.acme.multi.ledger.events.AccountOpened;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

public class OpenAccountDecider implements EventStreamDecider<OpenAccount, AccountEvent> {

    @Override
    public Optional<AccountEvent> handle(OpenAccount cmd, List<AccountEvent> events) {
        if (events.stream().anyMatch(e -> e instanceof AccountOpened)) {
            return Optional.empty();
        }
        if (cmd.currency() == null || cmd.currency().length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code");
        }
        return Optional.of(new AccountOpened(cmd.id(), cmd.currency()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return OpenAccount.class == command;
    }
}
