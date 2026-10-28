package com.example.library.lending.automations.loan_reminders;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import com.example.library.lending.config.LendingConfiguration;
import com.example.library.lending.events.BookBorrowed;
import com.example.library.lending.use_cases.schedule_reminder.ScheduleReminder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LoanRemindersProcessor extends EventProcessor {
    public LoanRemindersProcessor(EventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String getProcessorName() {
        return "LoanRemindersProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(LendingConfiguration.COPIES);
    }

    @MessageHandler
    void on(BookBorrowed event, OrderedMessage message) {
        commandBus.sendAndDontWait(new ScheduleReminder(event.copyId(), Math.max(1, event.loanDays() - 2)));
    }
}
