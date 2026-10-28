package com.example.library.lending.use_cases.borrow_book;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import com.example.library.lending.events.BookBorrowed;
import com.example.library.lending.events.BookReturned;
import org.junit.jupiter.api.Test;

class ReturnBookAfterLoanTest {

    @Test
    void lendsACopyThatCameBack() {
        var scenario = new GivenWhenThenScenario<>(new BorrowBookDecider());

        scenario
                .given(new BookBorrowed("c-3", "m-2", 7), new BookReturned("c-3", "m-2"))
                .when(new BorrowBook("c-3", "m-4", 7))
                .thenAssert(event -> {
                    if (!(event instanceof BookBorrowed)) throw new AssertionError("expected a loan");
                });
    }
}
