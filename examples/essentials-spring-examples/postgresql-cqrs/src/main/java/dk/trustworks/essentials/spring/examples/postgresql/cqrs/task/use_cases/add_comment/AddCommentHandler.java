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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.use_cases.add_comment;

import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler;
import dk.trustworks.essentials.reactive.command.CmdHandler;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Task;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.task.aggregates.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The single decision component of the {@code task.add_comment} slice — one command, one handler
 * (rules/slice-design.md §R1).
 * <p>
 * Two triggers reach this one slice: the HTTP endpoint beside it, and the {@code comment_on_task_created}
 * automation, which issues the same command when a task is created carrying an initial comment. The dedup
 * check lives on the {@code Task} aggregate, so both paths are idempotent.
 */
@Service
public class AddCommentHandler extends AnnotatedCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(AddCommentHandler.class);

    private final Tasks tasks;

    public AddCommentHandler(Tasks tasks) {
        this.tasks = requireNonNull(tasks, "No tasks provided");
    }

    // Automatically runs in a transaction as it's forwarded by the DurableLocalCommandBus
    @CmdHandler
    public void handle(AddComment cmd) {
        log.info("Adding comment '{}'", cmd);
        Task task = tasks.findTask(cmd.taskId()).orElseThrow();
        task.addComment(cmd.content(), cmd.createdAt());
    }
}
