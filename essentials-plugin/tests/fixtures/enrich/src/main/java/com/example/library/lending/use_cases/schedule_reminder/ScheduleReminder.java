package com.example.library.lending.use_cases.schedule_reminder;

import com.example.library.lending.routing.CopyCommand;

public record ScheduleReminder(String copyId, int dayOffset) implements CopyCommand {
}
