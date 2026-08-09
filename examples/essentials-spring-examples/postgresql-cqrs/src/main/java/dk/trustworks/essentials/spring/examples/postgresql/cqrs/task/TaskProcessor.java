/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.InTransactionEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.add_comment.AddComment;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.create_task.CreateTask;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Task;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.TaskCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Tasks;

import static java.util.Objects.nonNull;

@Service
public class TaskProcessor extends InTransactionEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(TaskProcessor.class);


    private final Tasks taskEventStoreRepository;

    protected TaskProcessor(Tasks taskEventStoreRepository,
                            EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies, true);
        this.taskEventStoreRepository = taskEventStoreRepository;
    }

    public Tasks getTaskEventStoreRepository() {
        return taskEventStoreRepository;
    }

    @Override
    public String getProcessorName() {
        return "TaskProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Tasks.AGGREGATE_TYPE);
    }

    @CmdHandler
    public void handle(CreateTask cmd) {
        log.info("Creating task with command '{}'", cmd);
        taskEventStoreRepository.createTask(cmd.taskId(), cmd);

    }

    @CmdHandler
    public void handle(AddComment cmd) {
        log.info("Adding comment '{}'", cmd);
        Task task = taskEventStoreRepository.findTask(cmd.taskId()).orElseThrow();
        task.addComment(cmd);
    }

    @MessageHandler
    void handle(TaskCreated event) {
        if (nonNull(event.comment())) {
            log.info("Task '{}' contains comment adding comment command", event);
            commandBus.send(new AddComment(event.taskId(), event.comment(), event.createdAt()));
        }
    }
}
