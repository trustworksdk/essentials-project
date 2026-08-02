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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.commands.CreateTask;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.domain.Task;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.domain.TaskId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.domain.events.TaskEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Component
public class TaskEventStoreRepository {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Task");
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private final StatefulAggregateRepository<TaskId, TaskEvent, Task> repository;

    public TaskEventStoreRepository(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        this.eventStore = eventStore;
        repository = StatefulAggregateRepository.from(eventStore,
                AGGREGATE_TYPE,
                reflectionBasedAggregateRootFactory(),
                Task.class);
    }

    public Optional<Task> findTask(TaskId taskId) {
        requireNonNull(taskId, "No taskId provided");
        return repository.tryLoad(taskId);
    }

    public Task getTask(TaskId taskId) {
        requireNonNull(taskId, "No taskId provided");
        return repository.load(taskId);
    }

    public Task createTask(TaskId taskId, CreateTask cmd) {
        requireNonNull(taskId, "No taskId provided");
        var task = new Task(taskId, cmd);
        return repository.save(task);
    }
}
