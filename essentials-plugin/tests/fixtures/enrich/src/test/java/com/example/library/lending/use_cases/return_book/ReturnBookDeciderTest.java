package com.example.library.lending.use_cases.return_book;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import com.example.library.lending.events.BookBorrowed;
import com.example.library.lending.events.BookReturned;
import org.junit.jupiter.api.Test;

class ReturnBookDeciderTest {

    @Test
    void takesBackALoanedCopy() {
        new GivenWhenThenScenario<>(new ReturnBookDecider())
                .given(new BookBorrowed("c-1", "m-7", 14))
                .when(new ReturnBook("c-1", "m-7"))
                .then(new BookReturned("c-1", "m-7"));
    }
}
