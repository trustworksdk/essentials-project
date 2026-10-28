package com.example.library.lending.events;

public record BookReturned(String copyId, String memberId) implements LendingEvent {
}
