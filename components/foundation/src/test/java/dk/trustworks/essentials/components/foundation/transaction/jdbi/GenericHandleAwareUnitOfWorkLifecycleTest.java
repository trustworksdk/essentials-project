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
import dk.trustworks.essentials.shared.Exceptions;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
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
                    return new GenericHandleAwareUnitOfWork(unitOfWorkFactory) {
                        @Override
                        protected void afterCommitting() {
                            hooksCalled.add("afterCommitting");
                        }

                        @Override
                        protected void afterRollback(Throwable cause) {
                            hooksCalled.add("afterRollback");
                        }
                    };
                }
            };

    /**
     * The subclass hooks - {@code EventStoreManagedUnitOfWork} publishes the committed events from {@code afterCommitting()}
     */
    private final List<String> hooksCalled = new ArrayList<>();

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

    /**
     * The rolled-back UnitOfWork used to stay current until the afterRollback callbacks had run, so a callback that opened
     * a UnitOfWork - e.g. to write a compensating change - joined the dead one, whose handle was closed, and its write was lost
     */
    @Test
    void a_unit_of_work_opened_from_after_rollback_is_a_new_one_and_commits() {
        var compensation = new RecordingCallback();
        var compensatingUnitOfWork = new ArrayList<UnitOfWork>();
        var callback = new RecordingCallback() {
            @Override
            public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
                super.afterRollback(unitOfWork, associatedResources, causeOfTheRollback);
                unitOfWorkFactory.usingUnitOfWork(newUnitOfWork -> {
                    compensatingUnitOfWork.add(newUnitOfWork);
                    newUnitOfWork.handle();
                    newUnitOfWork.registerLifecycleCallbackForResource("compensation", compensation);
                });
            }
        };
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("resource", callback);

        unitOfWork.rollback(new IllegalStateException("rolled back"));

        assertThat(compensatingUnitOfWork).hasSize(1);
        assertThat(compensatingUnitOfWork.get(0)).isNotSameAs(unitOfWork);
        assertThat(compensatingUnitOfWork.get(0).status()).isEqualTo(UnitOfWorkStatus.Committed);
        assertThat(compensation.calls).containsExactly("beforeCommit [compensation]", "afterCommit [compensation]");
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void an_error_from_after_commit_neither_escapes_commit_nor_stops_the_other_callbacks_or_after_committing() {
        var failing = new FailingCallback(() -> new AssertionError("afterCommit failed"));
        var other = new RecordingCallback();

        unitOfWorkFactory.usingUnitOfWork(unitOfWork -> {
            unitOfWork.registerLifecycleCallbackForResource("failing", failing);
            unitOfWork.registerLifecycleCallbackForResource("other", other);
        });

        assertThat(failing.calls).contains("afterCommit [failing]");
        assertThat(other.calls).containsExactly("beforeCommit [other]", "afterCommit [other]");
        assertThat(hooksCalled).containsExactly("afterCommitting");
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_critical_error_from_after_commit_propagates_but_after_committing_still_runs() {
        var failing = new FailingCallback(() -> new OutOfMemoryError("afterCommit failed"));
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("failing", failing);

        assertThatThrownBy(unitOfWork::commit).isInstanceOf(OutOfMemoryError.class);

        assertThat(unitOfWork.status()).isEqualTo(UnitOfWorkStatus.Committed);
        assertThat(hooksCalled).containsExactly("afterCommitting");
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void an_error_from_a_rollback_callback_neither_escapes_rollback_nor_stops_the_other_callbacks_or_after_rollback() {
        var failing = new FailingCallback(() -> new AssertionError("rollback callback failed"));
        var other = new RecordingCallback();
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("failing", failing);
        unitOfWork.registerLifecycleCallbackForResource("other", other);

        unitOfWork.rollback(new IllegalStateException("rolled back"));

        assertThat(failing.calls).containsExactly("beforeRollback [failing]", "afterRollback [failing]");
        assertThat(other.calls).containsExactly("beforeRollback [other]", "afterRollback [other]");
        assertThat(hooksCalled).containsExactly("afterRollback");
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_critical_error_from_after_rollback_propagates_but_after_rollback_still_runs() {
        var failing = new FailingCallback(() -> new OutOfMemoryError("afterRollback failed")) {
            @Override
            public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
                calls.add("beforeRollback " + associatedResources);
            }
        };
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("failing", failing);

        assertThatThrownBy(() -> unitOfWork.rollback(new IllegalStateException("rolled back"))).isInstanceOf(OutOfMemoryError.class);

        assertThat(unitOfWork.status()).isEqualTo(UnitOfWorkStatus.RolledBack);
        assertThat(hooksCalled).containsExactly("afterRollback");
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_critical_error_from_before_rollback_propagates_but_the_unit_of_work_is_still_rolled_back_and_removed() {
        var failing = new FailingCallback(() -> new OutOfMemoryError("beforeRollback failed"));
        var unitOfWork = unitOfWorkFactory.getOrCreateNewUnitOfWork();
        unitOfWork.registerLifecycleCallbackForResource("failing", failing);

        assertThatThrownBy(() -> unitOfWork.rollback(new IllegalStateException("rolled back"))).isInstanceOf(OutOfMemoryError.class);

        assertThat(unitOfWork.status()).isEqualTo(UnitOfWorkStatus.RolledBack);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    private static class RecordingCallback implements UnitOfWorkLifecycleCallback<String> {
        protected final List<String> calls = new ArrayList<>();

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

    /**
     * Records every call, and throws from every callback but {@code beforeCommit}
     */
    private static class FailingCallback extends RecordingCallback {
        private final Supplier<Throwable> failure;

        private FailingCallback(Supplier<Throwable> failure) {
            this.failure = failure;
        }

        @Override
        public void afterCommit(UnitOfWork unitOfWork, List<String> associatedResources) {
            super.afterCommit(unitOfWork, associatedResources);
            throw sneaky(failure.get());
        }

        @Override
        public void beforeRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            super.beforeRollback(unitOfWork, associatedResources, causeOfTheRollback);
            throw sneaky(failure.get());
        }

        @Override
        public void afterRollback(UnitOfWork unitOfWork, List<String> associatedResources, Throwable causeOfTheRollback) {
            super.afterRollback(unitOfWork, associatedResources, causeOfTheRollback);
            throw sneaky(failure.get());
        }

        private static RuntimeException sneaky(Throwable throwable) {
            return Exceptions.sneakyThrow(throwable);
        }
    }
}
