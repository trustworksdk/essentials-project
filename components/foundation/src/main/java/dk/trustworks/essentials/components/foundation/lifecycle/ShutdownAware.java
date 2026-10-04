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

package dk.trustworks.essentials.components.foundation.lifecycle;

/**
 * A component that should behave differently once the application is shutting down - typically one with background
 * work that retries database access, or a {@code stop()} that cleans up in the database.
 * <p>
 * The {@link DefaultLifecycleManager} calls {@link #shutdownStarting(ShutdownContext)} on every {@code ShutdownAware}
 * bean <b>before</b> it stops the first {@link dk.trustworks.essentials.shared.Lifecycle} bean. That matters because the
 * beans are stopped one after the other: without the signal, a component that has not been stopped yet cannot tell that
 * the call it is serving comes from another component's shutdown, and treats a database failure as it would while
 * running - by waiting and retrying.
 * <p>
 * An implementation should, from this call on:
 * <ul>
 *     <li>stop starting new background work against the database (lock acquiring, confirmation ticks, reconnects),
 *     interrupting any already blocked on it</li>
 *     <li>run each remaining database cleanup through {@link ShutdownContext#attemptCleanup(String, dk.trustworks.essentials.shared.functional.CheckedRunnable)}</li>
 * </ul>
 * It must return promptly and never throw. Without a {@link DefaultLifecycleManager} - a component started and stopped
 * by hand - this is never called, and the component's {@code stop()} behaves as it always has.
 */
public interface ShutdownAware {
    /**
     * The application has started shutting down.
     *
     * @param shutdown the shutdown's remaining budget and what it has learnt about the database
     */
    void shutdownStarting(ShutdownContext shutdown);
}
