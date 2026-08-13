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

import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.types.CharSequenceType;

/**
 * Identifies a task, and is the aggregate id of {@code Task} -- so it is also the stream id its events are written
 * under.
 *
 * <p>A semantic type rather than a bare {@code String}, so it cannot be swapped with any other identifier by
 * mistake.
 */
public class TaskId extends CharSequenceType<TaskId> {

    public TaskId(String value) {
        super(value);
    }

    public TaskId(CharSequence value) {
        super(value);
    }

    public static TaskId random() {
        return new TaskId(RandomIdGenerator.generate());
    }

    public static TaskId of(String id) {
        return new TaskId(id);
    }
}
