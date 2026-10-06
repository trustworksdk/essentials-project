package com.example.lanes.ledger.use_cases.open_account;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;

import java.util.List;
import java.util.Optional;

public class OpenAccountDecider implements EventStreamDecider<OpenAccount, Object> {
    @Override
    public Optional<Object> handle(OpenAccount cmd, List<Object> events) {
        return Optional.empty();
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return OpenAccount.class == command;
    }
}
