package com.example.library.lending.use_cases.schedule_reminder;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider;
import dk.trustworks.essentials.shared.FailFast;
import com.example.library.lending.events.LendingEvent;
import com.example.library.lending.events.ReminderScheduled;

import java.util.List;
import java.util.Optional;

/**
 * Plans the reminder a member receives before a loan falls due.
 */
public class ScheduleReminderDecider implements EventStreamDecider<ScheduleReminder, LendingEvent> {
    @Override
    public Optional<LendingEvent> handle(ScheduleReminder cmd, List<LendingEvent> events) {
        FailFast.requireFalse(cmd.dayOffset() > 30, "A reminder is never planned more than 30 days ahead");
        return Optional.of(new ReminderScheduled(cmd.copyId(), cmd.dayOffset(), "email"));
    }

    @Override
    public boolean canHandle(Class<?> command) {
        return ScheduleReminder.class == command;
    }
}
