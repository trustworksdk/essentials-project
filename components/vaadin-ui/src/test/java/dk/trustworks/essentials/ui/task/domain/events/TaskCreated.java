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

package dk.trustworks.essentials.ui.task.domain.events;

import dk.trustworks.essentials.ui.task.domain.TaskId;

import java.time.LocalDateTime;


public class TaskCreated implements TaskEvent {

    private TaskId taskId;
    private String comment;
    private LocalDateTime createdAt;
    private LocalDateTime completeAt;

    public TaskCreated(TaskId taskId, String comment, LocalDateTime createdAt, LocalDateTime completeAt) {
        this.taskId = taskId;
        this.comment = comment;
        this.createdAt = createdAt;
        this.completeAt = completeAt;
    }

    @Override
    public TaskId getTaskId() {
        return taskId;
    }

    public String getComment() {
        return comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompleteAt() {
        return completeAt;
    }
}
