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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.CommentAdded;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.TaskCreated;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.TaskEvent;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.Comment;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;

/**
 * A task and the comments made on it -- the smallest of the three example contexts, and the one that shows an
 * aggregate holding a growing collection rather than a scalar.
 *
 * <p>An event-sourced {@link AggregateRoot}: {@code addComment} applies a {@link CommentAdded} and the
 * {@code @EventHandler} below is the only place {@code comments} is written, so the set is rebuilt by replaying the
 * stream.
 *
 * <p>{@code addComment} de-duplicates against the comments it already holds, which makes a redelivered
 * {@code AddComment} harmless -- the {@code comment_on_task_created} automation issues it off an at-least-once
 * subscription. Note what the key covers: task, content <em>and</em> timestamp, so the same text posted at a
 * different instant is a different comment. See the slice's {@code CLAUDE.md} for what that does and does not
 * protect against.
 */
public class Task extends AggregateRoot<TaskId, TaskEvent, Task> {

    private Set<Comment> comments;

    public Task(TaskId aggregateId) {
        super(aggregateId);
    }

    public Task(TaskId aggregateId, String comment) {
        super(aggregateId);
        apply(new TaskCreated(aggregateId,
                comment,
                LocalDateTime.now()
        ));
    }

    public void addComment(String content, LocalDateTime createdAt) {
        Comment comment = new Comment(aggregateId(), content, createdAt);
        if (!comments.contains(comment)) {
            apply(new CommentAdded(aggregateId(), content, createdAt));
        }
    }

    public Set<Comment> getComments() {
        return comments;
    }

    @EventHandler
    private void on(TaskCreated event) {
        comments = new HashSet<>();
    }

    @EventHandler
    private void on(CommentAdded event) {
        comments.add(new Comment(event.taskId(), event.content(), event.createdAt()));
    }

    @Override
    public String toString() {
        return "Task(taskId=" + aggregateId() + ", comments=" + comments + ")";
    }
}
