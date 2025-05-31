/*
 * Copyright 2021-2025 the original author or authors.
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

package dk.trustworks.essentials.ui.task;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.ui.task.commands.CreateTask;
import dk.trustworks.essentials.ui.task.domain.*;
import dk.trustworks.essentials.ui.task.domain.events.TaskEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;


@Component
public class Tasks {

    public static final AggregateType                                                             AGGREGATE_TYPE = AggregateType.of("Task");
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration>       eventStore;
    private final StatefulAggregateRepository<TaskId, TaskEvent, Task>                            repository;

    public Tasks(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        this.eventStore = eventStore;
        repository = StatefulAggregateRepository.from(eventStore,
                AGGREGATE_TYPE,
                reflectionBasedAggregateRootFactory(),
                Task.class);
    }

    public Optional<Task> findTask(TaskId taskId) {
        return repository.tryLoad(taskId);
    }

    public Task getTask(TaskId taskId) {
        return repository.load(taskId);
    }

    public Task createTask(TaskId taskId, CreateTask cmd) {
        var task = new Task(taskId, cmd);
        return repository.save(task);
    }
}
