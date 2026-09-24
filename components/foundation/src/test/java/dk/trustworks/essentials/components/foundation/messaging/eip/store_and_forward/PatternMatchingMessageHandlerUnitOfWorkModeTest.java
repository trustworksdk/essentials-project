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

package dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward;

import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.Message;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PatternMatchingMessageHandler}, once it has been handed the {@link UnitOfWork} boundary via
 * {@link PatternMatchingMessageHandler#setUnitOfWorkFactory(UnitOfWorkFactory)}, honours each handler method's
 * {@link MessageHandler#unitOfWork()} mode.
 */
class PatternMatchingMessageHandlerUnitOfWorkModeTest {

    @Test
    void a_REQUIRED_handler_is_invoked_inside_a_committed_UnitOfWork() {
        var unitOfWorkFactory = new RecordingUnitOfWorkFactory();
        var handlerTarget     = new MixedModeHandlers(unitOfWorkFactory);
        var messageHandler    = new PatternMatchingMessageHandler(handlerTarget);
        messageHandler.setUnitOfWorkFactory(unitOfWorkFactory);

        messageHandler.accept(Message.of(new TransactionalEvent()));

        assertThat(handlerTarget.unitOfWorkActiveDuringTransactionalHandler).isTrue();
        assertThat(unitOfWorkFactory.createdUnitOfWorks).hasSize(1);
        assertThat(unitOfWorkFactory.createdUnitOfWorks.get(0).status).isEqualTo(UnitOfWorkStatus.Committed);
        assertThat(unitOfWorkFactory.getCurrentUnitOfWork()).isEmpty();
    }

    @Test
    void a_NONE_handler_is_invoked_without_any_UnitOfWork() {
        var unitOfWorkFactory = new RecordingUnitOfWorkFactory();
        var handlerTarget     = new MixedModeHandlers(unitOfWorkFactory);
        var messageHandler    = new PatternMatchingMessageHandler(handlerTarget);
        messageHandler.setUnitOfWorkFactory(unitOfWorkFactory);

        messageHandler.accept(Message.of(new BlockingIOEvent()));

        assertThat(handlerTarget.unitOfWorkActiveDuringBlockingHandler).isFalse();
        assertThat(unitOfWorkFactory.createdUnitOfWorks).isEmpty();
    }

    @Test
    void a_NONE_handler_can_open_a_UnitOfWork_itself_for_the_work_following_the_blocking_call() {
        var unitOfWorkFactory = new RecordingUnitOfWorkFactory();
        var handlerTarget     = new BlockingHandlerWithTransactionalTail(unitOfWorkFactory);
        var messageHandler    = new PatternMatchingMessageHandler(handlerTarget);
        messageHandler.setUnitOfWorkFactory(unitOfWorkFactory);

        messageHandler.accept(Message.of(new BlockingIOEvent()));

        assertThat(handlerTarget.unitOfWorkActiveDuringBlockingCall).isFalse();
        assertThat(handlerTarget.unitOfWorkActiveDuringTail).isTrue();
        assertThat(unitOfWorkFactory.createdUnitOfWorks).hasSize(1);
        assertThat(unitOfWorkFactory.createdUnitOfWorks.get(0).status).isEqualTo(UnitOfWorkStatus.Committed);
    }

    @Test
    void without_a_UnitOfWorkFactory_the_dispatcher_keeps_owning_the_boundary() {
        var handlerTarget  = new MixedModeHandlers(new RecordingUnitOfWorkFactory());
        var messageHandler = new PatternMatchingMessageHandler(handlerTarget);

        messageHandler.accept(Message.of(new TransactionalEvent()));

        // No UnitOfWorkFactory was handed over, so the handler is invoked as-is - historic behaviour, where whoever
        // dispatches the Message (e.g. Inboxes.DurableQueueBasedInbox) has already opened the UnitOfWork
        assertThat(handlerTarget.unitOfWorkActiveDuringTransactionalHandler).isFalse();
    }

    @Test
    void hasNonTransactionalMessageHandlers_detects_UnitOfWorkMode_NONE_handlers() {
        assertThat(new PatternMatchingMessageHandler(new MixedModeHandlers(new RecordingUnitOfWorkFactory())).hasNonTransactionalMessageHandlers()).isTrue();
        assertThat(new PatternMatchingMessageHandler(new OnlyTransactionalHandlers()).hasNonTransactionalMessageHandlers()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test payloads and handler targets
    // ------------------------------------------------------------------------------------------------------------

    record TransactionalEvent() {
    }

    record BlockingIOEvent() {
    }

    static class MixedModeHandlers {
        private final RecordingUnitOfWorkFactory unitOfWorkFactory;

        boolean unitOfWorkActiveDuringTransactionalHandler;
        boolean unitOfWorkActiveDuringBlockingHandler;

        MixedModeHandlers(RecordingUnitOfWorkFactory unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
        }

        @MessageHandler
        void on(TransactionalEvent e) {
            unitOfWorkActiveDuringTransactionalHandler = unitOfWorkFactory.hasActiveUnitOfWork();
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
            unitOfWorkActiveDuringBlockingHandler = unitOfWorkFactory.hasActiveUnitOfWork();
        }
    }

    static class OnlyTransactionalHandlers {
        @MessageHandler
        void on(TransactionalEvent e) {
        }
    }

    static class BlockingHandlerWithTransactionalTail {
        private final RecordingUnitOfWorkFactory unitOfWorkFactory;

        boolean unitOfWorkActiveDuringBlockingCall;
        boolean unitOfWorkActiveDuringTail;

        BlockingHandlerWithTransactionalTail(RecordingUnitOfWorkFactory unitOfWorkFactory) {
            this.unitOfWorkFactory = unitOfWorkFactory;
        }

        @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
        void on(BlockingIOEvent e) {
            // Stands in for the blocking call to an external system
            unitOfWorkActiveDuringBlockingCall = unitOfWorkFactory.hasActiveUnitOfWork();

            unitOfWorkFactory.usingUnitOfWork(() -> unitOfWorkActiveDuringTail = unitOfWorkFactory.hasActiveUnitOfWork());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Minimal UnitOfWork test doubles - the real UnitOfWorkFactory default methods drive create/join/commit
    // ------------------------------------------------------------------------------------------------------------

    static class RecordingUnitOfWorkFactory implements UnitOfWorkFactory<RecordingUnitOfWork> {
        final   List<RecordingUnitOfWork> createdUnitOfWorks = new ArrayList<>();
        private RecordingUnitOfWork       currentUnitOfWork;

        boolean hasActiveUnitOfWork() {
            return currentUnitOfWork != null;
        }

        @Override
        public RecordingUnitOfWork getRequiredUnitOfWork() {
            if (currentUnitOfWork == null) {
                throw new NoActiveUnitOfWorkException();
            }
            return currentUnitOfWork;
        }

        @Override
        public RecordingUnitOfWork getOrCreateNewUnitOfWork() {
            if (currentUnitOfWork == null) {
                currentUnitOfWork = new RecordingUnitOfWork(() -> currentUnitOfWork = null);
                createdUnitOfWorks.add(currentUnitOfWork);
                currentUnitOfWork.start();
            }
            return currentUnitOfWork;
        }

        @Override
        public Optional<RecordingUnitOfWork> getCurrentUnitOfWork() {
            return Optional.ofNullable(currentUnitOfWork);
        }
    }

    static class RecordingUnitOfWork implements UnitOfWork {
        private final Runnable         onCompleted;
        UnitOfWorkStatus status;
        private       Throwable        causeOfRollback;

        RecordingUnitOfWork(Runnable onCompleted) {
            this.onCompleted = onCompleted;
        }

        @Override
        public void start() {
            status = UnitOfWorkStatus.Started;
        }

        @Override
        public void commit() {
            status = UnitOfWorkStatus.Committed;
            onCompleted.run();
        }

        @Override
        public void rollback(Throwable cause) {
            status = UnitOfWorkStatus.RolledBack;
            causeOfRollback = cause;
            onCompleted.run();
        }

        @Override
        public UnitOfWorkStatus status() {
            return status;
        }

        @Override
        public Throwable getCauseOfRollback() {
            return causeOfRollback;
        }

        @Override
        public void markAsRollbackOnly(Throwable cause) {
            status = UnitOfWorkStatus.MarkedForRollbackOnly;
            causeOfRollback = cause;
        }

        @Override
        public <T> T registerLifecycleCallbackForResource(T resource, UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback) {
            return resource;
        }

        @Override
        public <T> List<T> getUnitOfWorkLifecycleCallbackResources(UnitOfWorkLifecycleCallback<T> associatedUnitOfWorkCallback) {
            return List.of();
        }
    }
}
