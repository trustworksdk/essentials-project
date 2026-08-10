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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;

import java.time.LocalDateTime;

/**
 * A task has been created, carrying the text it was opened with. The first event in every {@code Task} stream.
 *
 * <p>The {@code comment_on_task_created} automation reacts to it by issuing an {@code AddComment}, which is what
 * turns that opening text into an actual comment on the task.
 */
public record TaskCreated(TaskId taskId, String comment, LocalDateTime createdAt) implements TaskEvent {
}
