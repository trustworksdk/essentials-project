package com.example.library.lending.use_cases.borrow_book;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import com.example.library.lending.events.BookBorrowed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BorrowBookDeciderTest {

    @Test
    void lendsAnAvailableCopy() {
        var scenario = new GivenWhenThenScenario<>(new BorrowBookDecider());

        scenario
                .given()
                .when(new BorrowBook("c-1", "m-7", 14))
                .then(new BookBorrowed("c-1", "m-7", 14));
    }

    @Test
    @DisplayName("a copy out on loan is not lent twice")
    void refusesASecondLoan() {
        var scenario = new GivenWhenThenScenario<>(new BorrowBookDecider());

        scenario
                .given(new BookBorrowed("c-1", "m-7", 14))
                .when(new BorrowBook("c-1", "m-9", 7))
                .thenFailsWithExceptionType(IllegalStateException.class);
    }

    @Test
    void zeroDayLoan() {
        var scenario = new GivenWhenThenScenario<>(new BorrowBookDecider());
        var history = java.util.List.of(new BookBorrowed("c-2", "m-1", 3));

        scenario
                .given(history)
                .when(BorrowBookFixtures.zeroDays())
                .thenFailsWithException(new IllegalArgumentException("A loan lasts at least one day"));
    }
}
