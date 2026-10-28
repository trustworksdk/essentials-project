package com.example.library.lending.events;

public record ReminderScheduled(String copyId, int dayOffset, String channel) implements LendingEvent {
}
