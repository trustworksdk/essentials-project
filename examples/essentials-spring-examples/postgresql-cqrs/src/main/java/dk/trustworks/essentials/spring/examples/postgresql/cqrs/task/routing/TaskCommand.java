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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.routing;

import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;

/**
 * Marks a command as addressing a {@code Task}, and guarantees it can say which one.
 *
 * <p>It lives in {@code routing/} because that is its only job: giving every command in this context a common way to
 * expose the aggregate id, so dispatch does not need to know each concrete type. It is not a base class and carries
 * no behaviour -- a command slice still owns its own command record and its own handler.
 *
 * <p>The {@code banking} and {@code shipping} contexts have no equivalent; their commands are routed by type alone.
 * Both shapes are fine, and having one of each is deliberate.
 */
public interface TaskCommand {

    TaskId taskId();
}
