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

package dk.trustworks.essentials.reactive.command;

import java.util.Set;

/**
 * Common interface for all Command message handlers<br>
 * A command handler can choose support to 1 or more command types
 *
 * @see AnnotatedCommandHandler
 */
public interface CommandHandler {
    /**
     * This method is called by the {@link LocalCommandBus} to check if this concrete {@link CommandHandler} supports a given command type
     *
     * @param commandType the type of command being processed by the {@link LocalCommandBus}
     * @return true if this command handler can handle a command of the given type - otherwise it must return false
     */
    boolean canHandle(Class<?> commandType);

    /**
     * This method is called by the {@link LocalCommandBus} after it has determined that only this {@link CommandHandler} instance is capable of handling
     * the command (based on the commands type)
     *
     * @param command the command
     * @return the result of handling the command (if any)
     */
    Object handle(Object command);

    /**
     * The command types this {@link CommandHandler} declares that it handles - optional, and used for diagnostics only:
     * when a {@link CommandHandler} is added, the {@link CommandBus} checks these types (and those of the other
     * registered {@link CommandHandler}s) for a command type that more than one {@link CommandHandler}
     * {@link #canHandle(Class) can handle}, which would make every send of it fail with a
     * {@link MultipleCommandHandlersFoundException} (see {@link CommandBus#findCommandTypesHandledByMoreThanOneCommandHandler(CommandHandler)}).
     * <p>
     * Routing never uses this method - {@link #canHandle(Class)} alone decides which {@link CommandHandler} handles a
     * command. A declared type this handler cannot handle is ignored.
     * <p>
     * The default returns an empty set: the {@link CommandHandler} declares nothing, and is then only checked against
     * the types the other {@link CommandHandler}s declare. {@link AnnotatedCommandHandler} returns the parameter types
     * of its {@link CmdHandler}/{@link dk.trustworks.essentials.reactive.Handler} annotated methods.
     *
     * @return the command types this {@link CommandHandler} handles, as far as it can tell - never null
     */
    default Set<Class<?>> handledCommandTypes() {
        return Set.of();
    }
}
