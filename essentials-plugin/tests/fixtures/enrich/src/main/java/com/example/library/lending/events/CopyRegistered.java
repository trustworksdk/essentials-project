package com.example.library.lending.events;

public record CopyRegistered(String copyId, String isbn) implements LendingEvent {
}
