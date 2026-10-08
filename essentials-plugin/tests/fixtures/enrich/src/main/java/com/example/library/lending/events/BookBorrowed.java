package com.example.library.lending.events;

public record BookBorrowed(String copyId, String memberId, int loanDays) implements LendingEvent {
}
