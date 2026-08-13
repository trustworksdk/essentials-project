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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.automations.comment_on_task_created;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.InTransactionEventProcessor;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Tasks;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.TaskCreated;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.add_comment.AddComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.nonNull;

/**
 * The {@code task.comment_on_task_created} automation: when a task is created carrying an initial comment,
 * turn that comment into an explicit {@code AddComment} command so it lands as its own event.
 * <p>
 * This is an {@link InTransactionEventProcessor} rather than a plain {@code EventProcessor} because the
 * follow-up command must be applied in the same unit of work that appended {@code TaskCreated} — that
 * synchronous, strongly-consistent behaviour is what this example exists to demonstrate.
 * <p>
 * An automation reacts and issues a command; it never appends events itself, and it has no external API
 * (rules/slice-design.md § The four slice kinds).
 */
@Service
public class CommentOnTaskCreatedProcessor extends InTransactionEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(CommentOnTaskCreatedProcessor.class);

    protected CommentOnTaskCreatedProcessor(EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies, true);
    }

    @Override
    public String getProcessorName() {
        return "CommentOnTaskCreatedProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Tasks.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(TaskCreated event) {
        if (nonNull(event.comment())) {
            log.info("Task '{}' contains comment adding comment command", event);
            commandBus.send(new AddComment(event.taskId(), event.comment(), event.createdAt()));
        }
    }
}
