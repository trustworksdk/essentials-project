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

package dk.trustworks.essentials.ui.task.domain;


import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.ui.task.commands.*;
import dk.trustworks.essentials.ui.task.domain.events.*;

import java.time.LocalDateTime;
import java.util.*;

public class Task extends AggregateRoot<TaskId, TaskEvent, Task> {

    private Set<Comment> comments;
    private boolean completed;

    public Task(TaskId aggregateId) {
        super(aggregateId);
    }

    public Task(TaskId aggregateId, CreateTask cmd) {
        super(aggregateId);
        apply(new TaskCreated(aggregateId,
                              cmd.comment(),
                              LocalDateTime.now(),
                              cmd.completeAt()
        ));
    }

    public void addComment(AddComment cmd) {
        Comment comment = new Comment(cmd.taskId(), cmd.content(), cmd.createdAt());
        if (!comments.contains(comment)) {
            apply(new CommentAdded(cmd.taskId(), cmd.content(), cmd.createdAt()));
        }
    }

    public void complete(CompleteTask cmd) {
        if (completed) {
            return;
        }
        apply(new TaskCompleted(aggregateId(), LocalDateTime.now()));
    }

    @EventHandler
    private void on(TaskCreated event) {
        comments = new HashSet<>();
    }

    @EventHandler
    private void on(CommentAdded event) {
        comments.add(new Comment(event.getTaskId(), event.getContent(), event.getCreatedAt()));
    }

    @EventHandler
    private void on(TaskCompleted event) {
        completed = true;
    }
}
