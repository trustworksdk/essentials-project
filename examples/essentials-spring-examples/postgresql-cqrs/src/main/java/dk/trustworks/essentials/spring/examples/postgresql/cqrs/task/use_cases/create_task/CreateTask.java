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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.create_task;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.routing.TaskCommand;

/**
 * Create a task, opening it with the given text.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of {@code POST /tasks}. The caller
 * supplies the {@code TaskId}, which makes the command idempotent to retry from the client's side.
 *
 * <p>The opening text does not become a comment here -- {@code TaskCreated} carries it, and the
 * {@code comment_on_task_created} automation turns it into one by issuing {@code AddComment}.
 */
public record CreateTask(TaskId taskId, String comment) implements TaskCommand {
}
