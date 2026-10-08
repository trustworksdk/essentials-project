package com.example.library.lending;

import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario;
import com.example.library.lending.events.ReminderScheduled;
import com.example.library.lending.use_cases.schedule_reminder.ScheduleReminder;
import com.example.library.lending.use_cases.schedule_reminder.ScheduleReminderDecider;
import org.junit.jupiter.api.Test;

class ScheduleReminderTest {

    @Test
    void plansAReminder() {
        new GivenWhenThenScenario<>(new ScheduleReminderDecider())
                .given()
                .when(new ScheduleReminder("c-1", 3))
                .then(new ReminderScheduled("c-1", 3, channelFor("c-1")));
    }

    @Test
    void neverPlansFarAhead() {
        new GivenWhenThenScenario<>(new ScheduleReminderDecider())
                .given()
                .when(new ScheduleReminder("c-1", 45))
                .thenFailsWithExceptionType(IllegalArgumentException.class, "A reminder is never planned more than 30 days ahead");
    }

    private static String channelFor(String copyId) {
        return "email";
    }
}
