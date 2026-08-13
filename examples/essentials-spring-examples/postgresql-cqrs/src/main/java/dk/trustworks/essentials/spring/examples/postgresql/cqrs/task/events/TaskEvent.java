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

/**
 * The set of events a {@code Task} can emit is closed, so the interface is {@code sealed}: adding a variant means
 * updating the {@code permits} clause, which is a compile error away rather than a silent omission. Sealing does not
 * restrict the EventStore, which deserializes the concrete records reflectively by their fully qualified class name.
 */
public sealed interface TaskEvent permits TaskCreated, CommentAdded {

    TaskId taskId();
}
