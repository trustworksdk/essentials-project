/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.ui.task;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.ui.task.commands.*;
import dk.trustworks.essentials.ui.task.domain.Task;
import dk.trustworks.essentials.ui.task.domain.events.TaskCreated;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.List;

import static java.util.Objects.nonNull;

@Service
public class TaskProcessor extends InTransactionEventProcessor implements QueuedMessageHandler {

    private Logger log = LoggerFactory.getLogger(TaskProcessor.class);

    private final DurableQueues durableQueues;
    private final Tasks         tasks;

    private DurableQueueConsumer durableQueueConsumer;
    private final QueueName queueName = QueueName.of("task:autocomplete");

    protected TaskProcessor(Tasks tasks,
                            EventProcessorDependencies eventProcessorDependencies,
                            DurableQueues durableQueues) {
        super(eventProcessorDependencies, true);
        this.tasks = tasks;
        this.durableQueues = durableQueues;
    }

    @Override
    public String getProcessorName() {
        return "TaskProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Tasks.AGGREGATE_TYPE);
    }

    @Override
    public void start() {
        super.start();

        durableQueueConsumer = durableQueues.consumeFromQueue("TaskProcessor-consumer",
                                                              queueName,
                                                              RedeliveryPolicy.fixedBackoff(Duration.ofSeconds(1), 1),
                                                              1,
                                                              this);
        durableQueues.start();
    }

    @Override
    public void stop() {
        super.stop();

        if(durableQueueConsumer != null) {
            durableQueues.stop();
        }
    }

    @CmdHandler
    public void handle(CreateTask cmd) {
        log.info("Creating task with command '{}'", cmd);
        tasks.createTask(cmd.taskId(), cmd);
    }

    @CmdHandler
    public void handle(AddComment cmd) {
        log.info("Adding comment '{}'", cmd);
        Task task = tasks.findTask(cmd.taskId()).orElseThrow();
        task.addComment(cmd);
    }

    @CmdHandler
    public void handle(CompleteTask cmd) {
        log.info("Complete task '{}'", cmd);
        Task task = tasks.findTask(cmd.taskId()).orElseThrow();
        task.complete(cmd);
    }

    @MessageHandler
    void handle(TaskCreated event) {
        if (nonNull(event.getComment())) {
            log.info("Task '{}' contains comment adding comment command", event);
            commandBus.send(new AddComment(event.getTaskId(), event.getComment(), event.getCreatedAt()));
        }
        if (nonNull(event.getCompleteAt())) {
            log.info("Handling TaskCreated '{}'", event);
            durableQueues.queueMessage(queueName,
                    Message.of(event),
                    Duration.between(LocalDateTime.now(), event.getCompleteAt()));
        }
    }

    @Override
    public void handle(QueuedMessage queueMessage) {
        log.info("Received QueuedMessage '{}'", queueMessage);
    }
}
