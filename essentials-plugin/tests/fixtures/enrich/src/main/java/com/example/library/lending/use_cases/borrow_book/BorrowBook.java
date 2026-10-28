package com.example.library.lending.use_cases.borrow_book;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

import com.example.library.lending.routing.CopyCommand;

/**
 * Lends one copy of a book to a member for a fixed number of days.
 *
 * <p>The member must be known to the accounts context.
 */
public record BorrowBook(String copyId, String memberId, int loanDays) implements CopyCommand {
    public BorrowBook {
        requireNonNull(memberId, "memberId is required");
        requireTrue(loanDays > 0, "A loan lasts at least one day");
    }
}
