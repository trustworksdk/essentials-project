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

package dk.trustworks.essentials.components.foundation.transaction.spring;

import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory.SpringTransactionAwareHandleAwareUnitOfWork;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies the {@link SpringTransactionAwareUnitOfWork} copies of {@link UnitOfWork#getAllUnitOfWorkLifecycleCallbackResources()} and
 * {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()} - the cases {@code GenericHandleAwareUnitOfWorkTest} covers
 * for the Jdbi {@link UnitOfWork}. {@code ViewEventProcessor} decides between queueing a failed event and escalating it on
 * {@link UnitOfWork#hasLifecycleCallbackResourcesWithPendingChanges()}, and Spring Boot applications run on this implementation.
 * <p>
 * Registering resources doesn't need the underlying transaction, so neither a database nor a real transaction manager is involved.
 */
class SpringTransactionAwareUnitOfWorkTest {
    private final SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(() -> {
        throw new SQLException("No database in this test");
    }), mock(PlatformTransactionManager.class));

    @Test
    void no_resources_are_registered_in_a_new_unit_of_work() {
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);

        assertThat(unitOfWork.getAllUnitOfWorkLifecycleCallbackResources()).isEmpty();
    }

    @Test
    void all_resources_registered_are_returned_across_every_callback() {
        var unitOfWork     = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);
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
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);
        unitOfWork.registerLifecycleCallbackForResource("resource", new NoOpCallback<String>());

        assertThatThrownBy(() -> unitOfWork.getAllUnitOfWorkLifecycleCallbackResources().add("another resource"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_unit_of_work_without_resources_has_no_pending_changes() {
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isFalse();
    }

    @Test
    void resources_whose_callback_reports_no_pending_changes_are_not_pending_changes() {
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);
        var callback   = new PendingWhenStartsWithChangedCallback();
        unitOfWork.registerLifecycleCallbackForResource("loaded", callback);
        unitOfWork.registerLifecycleCallbackForResource("also loaded", callback);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isFalse();
    }

    @Test
    void a_single_resource_with_pending_changes_is_enough() {
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);
        var callback   = new PendingWhenStartsWithChangedCallback();
        unitOfWork.registerLifecycleCallbackForResource("loaded", callback);
        unitOfWork.registerLifecycleCallbackForResource("changed", callback);

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isTrue();
    }

    @Test
    void a_callback_that_does_not_override_hasPendingChanges_counts_every_resource_as_pending() {
        var unitOfWork = new SpringTransactionAwareHandleAwareUnitOfWork(unitOfWorkFactory);
        unitOfWork.registerLifecycleCallbackForResource("loaded", new PendingWhenStartsWithChangedCallback());
        unitOfWork.registerLifecycleCallbackForResource("unknown", new NoOpCallback<String>());

        assertThat(unitOfWork.hasLifecycleCallbackResourcesWithPendingChanges()).isTrue();
    }

    private static class PendingWhenStartsWithChangedCallback extends NoOpCallback<String> {
        @Override
        public boolean hasPendingChanges(String resource) {
            return resource.startsWith("changed");
        }
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
