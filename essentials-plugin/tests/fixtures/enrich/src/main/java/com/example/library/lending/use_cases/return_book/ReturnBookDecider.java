package com.example.library.lending.use_cases.return_book;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import com.example.library.lending.events.BookBorrowed;
import com.example.library.lending.events.BookReturned;
import com.example.library.lending.events.LendingEvent;

import java.util.List;
import java.util.Optional;

public class ReturnBookDecider implements EventStreamDecider<ReturnBook, LendingEvent> {
    @Override
    public Optional<LendingEvent> handle(ReturnBook cmd, List<LendingEvent> events) {
        var last = events.isEmpty() ? null : events.get(events.size() - 1);
        if (!(last instanceof BookBorrowed)) {
            throw new IllegalStateException("Only a copy on loan can be returned");
        }
        return Optional.of(new BookReturned(cmd.copyId(), cmd.memberId()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return ReturnBook.class == command;
    }
}
