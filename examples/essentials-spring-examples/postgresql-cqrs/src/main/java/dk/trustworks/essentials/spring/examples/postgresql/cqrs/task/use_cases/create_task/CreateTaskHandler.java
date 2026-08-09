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

import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code task.create_task} slice — one command, one handler
 * (rules/slice-design.md §R1).
 */
@Service
public class CreateTaskHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CreateTaskHandler.class);

    private final Tasks tasks;

    public CreateTaskHandler(Tasks tasks) {
        this.tasks = requireNonNull(tasks, "No tasks provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(CreateTask cmd) {
        log.info("Creating task with command '{}'", cmd);
        tasks.createTask(cmd.taskId(), cmd);
    }
}
