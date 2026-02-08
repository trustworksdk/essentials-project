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

package dk.trustworks.essentials.ui.task;

 import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
 import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
 import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
 import dk.trustworks.essentials.ui.task.domain.events.*;
 import dk.trustworks.essentials.ui.task.views.TaskView;
 import org.slf4j.*;
 import org.springframework.stereotype.Service;

 import java.util.*;
 import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskProcessor2 extends EventProcessor {

    private final Logger logger = LoggerFactory.getLogger(TaskProcessor2.class);

    public Map<String, TaskView> taskViews = new ConcurrentHashMap<>();

    public TaskProcessor2(EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies);
    }

    @Override
    public String getProcessorName() {
        return "TaskProcessor2";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Tasks.AGGREGATE_TYPE);
    }

    @MessageHandler
    void handle(TaskCreated event) {
        if (!taskViews.containsKey(event.getTaskId().toString())) {
            logger.info("Task view '{}' created,", event);
            taskViews.put(event.getTaskId().toString(), TaskView.create(event.getTaskId(), event.getComment(), event.getCreatedAt()));
        } else {
            logger.info("Task view '{}' already exists,", event);
        }
    }

    @MessageHandler
    void handle(CommentAdded event) {
        if (taskViews.containsKey(event.getTaskId().toString())) {
            logger.info("Task view '{}' comment added,", event);
            taskViews.computeIfPresent(event.getTaskId().toString(), (k, taskView) -> taskView.addComment(event.getContent()));
        } else {
            logger.info("Task view '{}' does not exist,", event);
        }
    }

    @MessageHandler
    void handle(TaskCompleted event) {
        if (taskViews.containsKey(event.getTaskId().toString())) {
            logger.info("Task view '{}' completed,", event);
            taskViews.computeIfPresent(event.getTaskId().toString(), (k, taskView) -> taskView.complete(event.getCompletedAt()));
        } else {
            logger.info("Task view '{}' does not exist,", event);
        }
    }
}
