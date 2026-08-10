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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.add_comment;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;

import java.time.LocalDateTime;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.routing.TaskCommand;

/**
 * Add a comment to an existing task.
 *
 * <p>Both the command and the request body of {@code POST /tasks/comments}. Like {@code ShipOrder} in the shipping
 * context it has two triggers -- that endpoint, and the {@code comment_on_task_created} automation -- and the second
 * is at-least-once, which is why {@code Task.addComment} de-duplicates.
 *
 * <p>{@code createdAt} is supplied by the caller rather than taken from the clock in the handler. That is what makes
 * a redelivery carry the same timestamp as the original, and therefore be recognised as the same comment; it is part
 * of the dedup key, not decoration.
 */
public record AddComment(TaskId taskId, String content, LocalDateTime createdAt) implements TaskCommand {
}
