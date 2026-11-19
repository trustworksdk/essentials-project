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
package dk.trustworks.essentials.components.eventlog.processor;

import dk.trustworks.essentials.components.eventlog.ErrorSettings;
import dk.trustworks.essentials.components.eventlog.processor.test_data.Added;
import dk.trustworks.essentials.components.foundation.causation.CausationContext;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link EventLogInboxProcessor} on {@link PostgresqlDurableQueues} sharing the page's JDBI unit
 * of work factory: every event forwarded exactly once even when a page fails after an enqueue and is redelivered - with a
 * must-fail arm that enqueues outside the page transaction - and the payload filter, the forwarded metadata and the
 * cause bound in the handler, an unregistered aggregate type registered at start, a stop and restart.
 */
class EventLogInboxProcessorIT extends AbstractProcessorIT {
    private static final long   X        = 37;
    private static final String INVOICES = "Invoices";

    private PostgresqlDurableQueues durableQueues;

    /** A payload no handler takes */
    record Noted(String note) {
    }

    @BeforeEach
    void setUpQueues() {
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setJsonSerializer(serializer)
                                               .setMessageHandlingTimeout(Duration.ofSeconds(5))
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .build();
        durableQueues.start();
    }

    @AfterEach
    void stopQueues() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
    }

    /** Counts each handled amount (= global order of an {@link #appendEvents(long)} event) and records what it saw */
    class Forwarding extends EventLogInboxProcessor {
        final Map<Integer, Integer>       handled  = new ConcurrentHashMap<>();
        final Map<Integer, String>        causedBy = new ConcurrentHashMap<>();
        final Map<Integer, MessageMetaData> metaData = new ConcurrentHashMap<>();

        Forwarding(List<String> aggregateTypes, Inboxes inboxes, Set<ProcessorMutant> mutants) {
            super("forwarding", aggregateTypes, new EventLogProcessorDependencies(eventLog, serializer, source(SourceKind.JDBI, Set.of())), inboxes, unitOfWorkFactory);
            seams(AbstractProcessorIT.seams(mutants, Set.of()));
        }

        /** PostgresqlDurableQueues keeps no key in order across instances; one test instance needs no fenced lock */
        @Override
        protected MessageConsumptionMode inboxMessageConsumptionMode() {
            return MessageConsumptionMode.GlobalCompetingConsumers;
        }

        @Override
        protected int forwardPageSize() {
            return 50;
        }

        @Override
        protected Optional<ErrorSettings> errorSettings() {
            return Optional.of(errors());
        }

        @MessageHandler
        void on(Added added, OrderedMessage message) {
            handled.merge(added.amount(), 1, Integer::sum);
            CausationContext.current().ifPresent(cause -> causedBy.put(added.amount(), cause.toString()));
            metaData.put(added.amount(), message.getMetaData());
        }

        long undelivered() {
            return inbox().orElseThrow().getNumberOfUndeliveredMessages();
        }
    }

    private Inboxes inboxes() {
        return Inboxes.durableQueueBasedInboxes(durableQueues, mock(FencedLockManager.class));
    }

    /**
     * Inboxes whose inbox fails the enqueue of amount {@link #X} once, after the message was enqueued: the page fails
     * after its enqueues and is redelivered
     */
    private Inboxes failingOnceAfterEnqueueOfX(AtomicBoolean failed) {
        var real = inboxes();
        return proxy(Inboxes.class, (proxy, method, args) -> {
            var result = invoke(method, real, args);
            if (!(result instanceof Inbox inbox)) {
                return result;
            }
            return proxy(Inbox.class, (inboxProxy, inboxMethod, inboxArgs) -> {
                var inboxResult = invoke(inboxMethod, inbox, inboxArgs);
                if (inboxMethod.getName().equals("addMessageReceived") && inboxArgs[0] instanceof Message message
                        && message.getPayload() instanceof Added added && added.amount() == X && failed.compareAndSet(false, true)) {
                    throw new IllegalStateException("fails once after the enqueue of " + X);
                }
                return inboxResult == inbox ? inboxProxy : inboxResult;
            });
        });
    }

    @Test
    void every_event_is_forwarded_exactly_once_when_a_page_fails_after_an_enqueue() throws Exception {
        exactlyOnceArm(Set.of());
    }

    @Test
    void must_fail_INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION() {
        assertThatThrownBy(() -> exactlyOnceArm(Set.of(ProcessorMutant.INBOX_FORWARD_OUTSIDE_PAGE_TRANSACTION))).isInstanceOf(AssertionError.class);
    }

    private void exactlyOnceArm(Set<ProcessorMutant> mutants) throws Exception {
        appendEvents(-1);
        var failed    = new AtomicBoolean();
        var processor = new Forwarding(List.of(ORDERS), failingOnceAfterEnqueueOfX(failed), mutants);
        processor.start();
        try {
            var key = processor.projections().getFirst().key();
            awaitCaughtUp(key);
            awaitTrue("every event handled and the inbox drained", Duration.ofSeconds(30),
                      () -> processor.handled.size() == EVENTS && processor.undelivered() == 0);
            assertThat(failed).as("the page failed after an enqueue").isTrue();
            assertThat(processor.handled).allSatisfy((amount, times) -> assertThat(times).as("forwarded once: " + amount).isEqualTo(1));
            assertThat(parked(key)).isEmpty();
        } finally {
            processor.stop();
        }
    }

    @Test
    void forwards_handled_payloads_with_metadata_and_cause_and_follows_an_unregistered_type_across_a_restart() throws Exception {
        appendEvents(-1);
        appendOne(ORDERS, "o0", new Noted("no handler"));
        var processor = new Forwarding(List.of(ORDERS, INVOICES), inboxes(), Set.of());
        processor.start();
        try {
            assertThat(processor.isStarted()).isTrue();
            assertThat(processor.projections()).extracting(projection -> projection.key().aggregateType()).containsExactly(ORDERS, INVOICES);
            var key = processor.projections().getFirst().key();
            awaitTrue("caught up past the unhandled event", Duration.ofSeconds(20), () -> resumePoint(key) > EVENTS + 1);
            awaitTrue("every event handled", Duration.ofSeconds(20), () -> processor.handled.size() == EVENTS);
            assertThat(processor.undelivered()).as("the unhandled payload is not forwarded").isZero();
            assertThat(processor.metaData.get(1)).containsKey(EventLogInboxProcessor.EVENT_ID_METADATA_KEY);
            assertThat(processor.causedBy.get(1)).isEqualTo(processor.metaData.get(1).get(EventLogInboxProcessor.EVENT_ID_METADATA_KEY));

            processor.stop();
            assertThat(processor.isStarted()).isFalse();
            processor.start();
            appendOne(INVOICES, "i1", new Added("i1", 1000));
            awaitTrue("the restarted processor forwards the registered type's event", Duration.ofSeconds(20), () -> processor.handled.containsKey(1000));
            assertThat(processor.handled).allSatisfy((amount, times) -> assertThat(times).as("forwarded once: " + amount).isEqualTo(1));
        } finally {
            processor.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
