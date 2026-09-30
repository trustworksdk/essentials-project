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

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies {@link GenericHandleAwareUnitOfWork#getAllUnitOfWorkLifecycleCallbackResources()} - registering resources
 * doesn't need the underlying transaction, so no database is involved.
 */
class GenericHandleAwareUnitOfWorkTest {
    private final GenericHandleAwareUnitOfWorkFactory<GenericHandleAwareUnitOfWork> unitOfWorkFactory =
            new GenericHandleAwareUnitOfWorkFactory<>(Jdbi.create(() -> {
                throw new SQLException("No database in this test");
            })) {
                @Override
                protected GenericHandleAwareUnitOfWork createNewUnitOfWorkInstance(GenericHandleAwareUnitOfWorkFactory<GenericHandleAwareUnitOfWork> unitOfWorkFactory) {
                    return new GenericHandleAwareUnitOfWork(unitOfWorkFactory);
                }
            };

    @Test
    void no_resources_are_registered_in_a_new_unit_of_work() {
        var unitOfWork = new GenericHandleAwareUnitOfWork(unitOfWorkFactory);

        assertThat(unitOfWork.getAllUnitOfWorkLifecycleCallbackResources()).isEmpty();
    }

    @Test
    void all_resources_registered_are_returned_across_every_callback() {
        var unitOfWork     = new GenericHandleAwareUnitOfWork(unitOfWorkFactory);
        var stringCallback = new NoOpCallback<String>();
        var numberCallback = new NoOpCallback<Integer>();

        unitOfWork.registerLifecycleCallbackForResource("first", stringCallback);
        unitOfWork.registerLifecycleCallbackForResource("second", stringCallback);
        unitOfWork.registerLifecycleCallbackForResource(3, numberCallback);

        assertThat(unitOfWork.getAllUnitOfWorkLifecycleCallbackResources()).containsExactlyInAnyOrder("first", "second", 3);
        assertThat(unitOfWork.getUnitOfWorkLifecycleCallbackResources(stringCallback)).containsExactly("first", "second");
    }

    @Test
    void the_returned_resources_cannot_be_modified() {
        var unitOfWork = new GenericHandleAwareUnitOfWork(unitOfWorkFactory);
        unitOfWork.registerLifecycleCallbackForResource("resource", new NoOpCallback<String>());

        assertThatThrownBy(() -> unitOfWork.getAllUnitOfWorkLifecycleCallbackResources().add("another resource"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static class NoOpCallback<T> implements UnitOfWorkLifecycleCallback<T> {
        @Override
        public BeforeCommitProcessingStatus beforeCommit(UnitOfWork unitOfWork, List<T> associatedResources) {
            return BeforeCommitProcessingStatus.COMPLETED;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<T> associatedResources) {
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<T> associatedResources, Throwable causeOfTheRollback) {
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<T> associatedResources, Throwable causeOfTheRollback) {
        }
    }
}
