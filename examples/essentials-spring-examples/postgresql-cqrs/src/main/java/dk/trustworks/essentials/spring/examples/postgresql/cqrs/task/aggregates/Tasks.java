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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.types.TaskId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.events.TaskEvent;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Task} aggregates, and the owner of the {@code Task} {@link AggregateType} -- the name
 * under which their events are stored, and the one the {@code comment_on_task_created} automation subscribes to.
 *
 * <p>It wraps a {@link StatefulAggregateRepository}, which loads an aggregate by replaying its stream and persists
 * the events a command produced.
 *
 * <p>{@link #createNewTask} persists an already-constructed task rather than building one: constructing it is what
 * emits {@code TaskCreated}, and that decision belongs to the slice, not here. Same shape as
 * {@code Accounts.openNewAccount}, {@code ShippingOrders.registerNewOrder} and
 * {@code IntraBankMoneyTransfers.requestNewTransfer}.
 */
@Component
public class Tasks {

    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("Task");
    private final ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private final StatefulAggregateRepository<TaskId, TaskEvent, Task> repository;

    public Tasks(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
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

    /**
     * Persists an already-constructed {@link Task}. Constructing it — which is what emits {@code TaskCreated} — is
     * the {@code task.create_task} slice's decision, not this repository's, so it happens there. Mirrors
     * {@code Accounts.openNewAccount}, {@code ShippingOrders.registerNewOrder} and
     * {@code IntraBankMoneyTransfers.requestNewTransfer}.
     */
    public Task createNewTask(Task task) {
        requireNonNull(task, "No task provided");
        return repository.save(task);
    }
}
