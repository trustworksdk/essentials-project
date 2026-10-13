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

package dk.trustworks.essentials.components.foundation.transaction.jdbi;

import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The after-commit and after-rollback callbacks run, and are given the resources registered for them.
 * <p>
 * Closing the handle clears the registered resources, and both {@code commit()} and {@code rollback()} closed it
 * before calling the callbacks - so with this factory, the JDBI one and the event store's, no
 * {@link UnitOfWorkLifecycleCallback#afterCommit} or {@link UnitOfWorkLifecycleCallback#afterRollback} ever ran.
 * Spring's factory has its own commit path and was not affected. A mocked {@link Connection} is all the handle needs.
 */
class GenericHandleAwareUnitOfWorkLifecycleTest {
    private final GenericHandleAwareUnitOfWorkFactory<GenericHandleAwareUnitOfWork> unitOfWorkFactory =
            new GenericHandleAwareUnitOfWorkFactory<>(Jdbi.create(() -> mock(Connection.class))) {
                @Override
                protected GenericHandleAwareUnitOfWork createNewUnitOfWorkInstance(GenericHandleAwareUnitOfWorkFactory<GenericHandleAwareUnitOfWork> unitOfWorkFactory) {
                    return new GenericHandleAwareUnitOfWork(unitOfWorkFactory);
                }
            };

    @Test
    void after_commit_is_called_with_the_registered_resources() {
        var callback = new RecordingCallback();

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.registerLifecycleCallbackForResource("first", callback);
            unitOfWork.registerLifecycleCallbackForResource("second", callback);
        });

        assertThat(callback.calls).containsExactly("beforeCommit [first, second]", "afterCommit [first, second]");
    }

    @Test
    void after_rollback_is_called_with_the_registered_resources() {
        var callback = new RecordingCallback();
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("resource", callback);

        unitOfWork.rollback(new IllegalStateException("rolled back"));

        assertThat(callback.calls).containsExactly("beforeRollback [resource]", "afterRollback [resource]");
    }

    private static class RecordingCallback implements UnitOfWorkLifecycleCallback<String> {
        private final List<String> calls = new ArrayList<>();

        @Override
        public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
            calls.add("beforeCommit " + associatedResources);
            return BeforeCommitProcessingStatus.COMPLETED;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
            calls.add("afterCommit " + associatedResources);
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            calls.add("beforeRollback " + associatedResources);
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            calls.add("afterRollback " + associatedResources);
        }
    }
}
