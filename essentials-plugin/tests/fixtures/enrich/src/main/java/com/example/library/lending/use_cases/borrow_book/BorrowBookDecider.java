package com.example.library.lending.use_cases.borrow_book;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import com.example.library.lending.events.BookBorrowed;
import com.example.library.lending.events.BookReturned;
import com.example.library.lending.events.LendingEvent;

import java.util.List;
import java.util.Optional;

public class BorrowBookDecider implements EventStreamDecider<BorrowBook, LendingEvent> {
    @Override
    public Optional<LendingEvent> handle(BorrowBook cmd, List<LendingEvent> events) {
        if (cmd.copyId() == null) {
            throw new IllegalArgumentException("copyId is required");
        }
        var onLoan = false;
        for (var e : events) {
            if (e instanceof BookBorrowed) onLoan = true;
            if (e instanceof BookReturned) onLoan = false;
        }
        if (onLoan) {
            // throw new IllegalStateException("A reserved copy cannot be lent");
            throw new IllegalStateException("A copy that is out on loan cannot be borrowed again");
        }
        return Optional.of(new BookBorrowed(cmd.copyId(), cmd.memberId(), cmd.loanDays()));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return BorrowBook.class == command;
    }
}
