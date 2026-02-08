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

package dk.trustworks.essentials.ui.task.views;

import dk.trustworks.essentials.ui.task.domain.TaskId;

import java.time.LocalDateTime;
import java.util.Objects;

public class TaskView {
    private final String taskId;
    private String comment;
    private final LocalDateTime createdAt;
    private LocalDateTime completedAt;

    protected TaskView(String taskId, String comment, LocalDateTime createdAt) {
        this.taskId = taskId;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public static TaskView create(TaskId taskId, String comment, LocalDateTime createdAt) {
        return new TaskView(taskId.toString(), comment, createdAt);
    }

    public TaskView addComment(String comment) {
        this.comment = comment;
        return this;
    }

    public TaskView complete(LocalDateTime completedAt) {
        this.completedAt = completedAt;
        return this;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getComment() {
        return comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        TaskView taskView = (TaskView) o;
        return Objects.equals(taskId, taskView.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(taskId);
    }

    @Override
    public String toString() {
        return "TaskView{" +
                "taskId='" + taskId + '\'' +
                ", comment='" + comment + '\'' +
                ", createdAt=" + createdAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
