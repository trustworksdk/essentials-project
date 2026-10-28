package com.example.library.lending.use_cases.register_copy;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import com.example.library.lending.events.CopyRegistered;
import com.example.library.lending.events.LendingEvent;

import java.util.List;
import java.util.Optional;

public class RegisterCopyDecider implements EventStreamDecider<RegisterCopy, LendingEvent> {
    @Override
    public Optional<LendingEvent> handle(RegisterCopy cmd, List<LendingEvent> events) {
        if (!events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CopyRegistered(cmd.copyId(), cmd.isbn()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return RegisterCopy.class == command;
    }
}
