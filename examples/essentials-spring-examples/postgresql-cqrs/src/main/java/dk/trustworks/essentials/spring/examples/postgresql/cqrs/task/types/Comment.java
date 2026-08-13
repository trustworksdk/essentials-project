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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types;

import java.time.LocalDateTime;

/**
 * A single comment on a task: an immutable value object with no identity beyond its own fields.
 *
 * <p>That matters, because {@code Task} holds its comments in a {@code Set} and relies on the record's generated
 * {@code equals} to decide whether a comment is already there. All three components take part, so two comments with
 * the same text on the same task are distinct if their timestamps differ.
 *
 * <p>It is the in-memory shape only -- what is stored is the {@code CommentAdded} event.
 */
public record Comment(TaskId taskId, String content, LocalDateTime createdAt) {
}
