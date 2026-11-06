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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedConsumer;
import dk.trustworks.essentials.shared.functional.CheckedFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test helper that returns a {@link HandleAwareUnitOfWorkFactory} stub which runs the
 * caller-supplied lambda inline against a mock {@link HandleAwareUnitOfWork}. Used by tests
 * that exercise framework code wrapping repository calls in {@code withUnitOfWork(...)}.
 */
final class InlineUnitOfWorkFactories {
    private InlineUnitOfWorkFactories() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> inline() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(HandleAwareUnitOfWorkFactory.class);
        var uow = mock(HandleAwareUnitOfWork.class);
        try {
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                return function.apply(uow);
            });
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                consumer.accept(uow);
                return null;
            }).when(factory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }

    /**
     * Like {@link #inline()}, but each outermost {@code withUnitOfWork}/{@code usingUnitOfWork} gets its own mock
     * {@link HandleAwareUnitOfWork}, a nested call joins it, and {@code getCurrentUnitOfWork()} answers the one in
     * progress on the calling thread - as the real factories do. Lets a test tell which unit of work a call ran in.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> joining() {
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> factory = mock(HandleAwareUnitOfWorkFactory.class);
        var current = new ThreadLocal<HandleAwareUnitOfWork>();
        try {
            when(factory.getCurrentUnitOfWork()).thenAnswer(invocation -> java.util.Optional.ofNullable(current.get()));
            when(factory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(invocation -> {
                CheckedFunction<HandleAwareUnitOfWork, ?> function = invocation.getArgument(0);
                if (current.get() != null) {
                    return function.apply(current.get());
                }
                current.set(mock(HandleAwareUnitOfWork.class));
                try {
                    return function.apply(current.get());
                } finally {
                    current.remove();
                }
            });
            doAnswer(invocation -> {
                CheckedConsumer<HandleAwareUnitOfWork> consumer = invocation.getArgument(0);
                if (current.get() != null) {
                    consumer.accept(current.get());
                    return null;
                }
                current.set(mock(HandleAwareUnitOfWork.class));
                try {
                    consumer.accept(current.get());
                } finally {
                    current.remove();
                }
                return null;
            }).when(factory).usingUnitOfWork(any(CheckedConsumer.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return factory;
    }
}
