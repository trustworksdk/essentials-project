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

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

/**
 * {@link AbstractCommandBus} used to keep its handlers in a plain {@link HashSet}, added to with a check-then-add, and
 * cleared its command type cache outside any lock - so concurrent registrations could lose a handler, and a command type
 * resolved while a handler was being added could stay cached with the handler that was the only match before it.
 */
class LocalCommandBusConcurrentRegistrationTest {
    private static final Duration AT_MOST = Duration.ofSeconds(10);

    private LocalCommandBus               commandBus;
    private ch.qos.logback.classic.Logger busLogger;
    private Level                         originalLevel;

    @BeforeEach
    void setUp() {
        commandBus = new LocalCommandBus();
        busLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LocalCommandBus.class);
        originalLevel = busLogger.getLevel();
        // One INFO line per registration would drown the test output
        busLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        busLogger.setLevel(originalLevel);
    }

    @Test
    @Timeout(60)
    void handlers_added_from_many_threads_are_all_registered_while_commands_are_sent() throws Exception {
        var threads           = 16;
        var handlersPerThread = 50;
        var orderHandler      = new TypeCommandHandler(CreateOrder.class);
        commandBus.addCommandHandler(orderHandler);

        var start   = new CountDownLatch(1);
        var added   = ConcurrentHashMap.<CommandHandler>newKeySet();
        var sending = new AtomicBoolean(true);
        try (var executor = Executors.newFixedThreadPool(threads + 2)) {
            // Senders resolve a command type the whole time, so the cache is filled and cleared under the registrations
            var senders = List.of(executor.submit(() -> sendUntilStopped(start, sending, orderHandler)),
                                  executor.submit(() -> sendUntilStopped(start, sending, orderHandler)));
            var registrations = new ArrayList<Future<?>>();
            for (var thread = 0; thread < threads; thread++) {
                registrations.add(executor.submit(() -> {
                    awaitQuietly(start);
                    for (var i = 0; i < handlersPerThread; i++) {
                        var handler = new TypeCommandHandler(Unhandled.class);
                        added.add(handler);
                        commandBus.addCommandHandler(handler);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var registration : registrations) {
                registration.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
            }
            sending.set(false);
            for (var sender : senders) {
                assertThat(sender.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS)).as("resolutions made by a sender").isPositive();
            }
        }

        assertThat(added).hasSize(threads * handlersPerThread);
        assertThat(commandBus.getCommandHandlers()).as("no registration lost")
                                                   .hasSize(threads * handlersPerThread + 1)
                                                   .containsAll(added)
                                                   .first().isSameAs(orderHandler);
        assertThat(commandBus.findCommandHandlerCapableOfHandling(new CreateOrder())).isSameAs(orderHandler);
        assertThat(commandBus.findCommandHandlersCapableOfHandling(Unhandled.class)).hasSize(threads * handlersPerThread);
    }

    @Test
    @Timeout(60)
    void handlers_added_and_removed_from_many_threads_leave_exactly_the_handlers_not_removed() throws Exception {
        var threads = 16;
        var kept    = ConcurrentHashMap.<CommandHandler>newKeySet();
        var start   = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var futures = new ArrayList<Future<?>>();
            for (var thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    awaitQuietly(start);
                    for (var i = 0; i < 50; i++) {
                        var keep   = new TypeCommandHandler(Unhandled.class);
                        var remove = new TypeCommandHandler(Unhandled.class);
                        commandBus.addCommandHandler(keep);
                        commandBus.addCommandHandler(remove);
                        commandBus.removeCommandHandler(remove);
                        kept.add(keep);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
            }
        }

        assertThat(commandBus.getCommandHandlers()).containsExactlyInAnyOrderElementsOf(kept);
    }

    /**
     * A command type resolved from the handlers as they were before a registration must not stay cached after it: the
     * registration waits for the resolution in flight, then clears the cache
     */
    @Test
    @Timeout(60)
    void a_resolution_in_flight_during_a_registration_is_not_cached_past_it() throws Exception {
        var insideCanHandle  = new CountDownLatch(1);
        var releaseCanHandle = new CountDownLatch(1);
        var firstHandler = new TypeCommandHandler(CreateOrder.class) {
            @Override
            public boolean canHandle(Class<?> commandType) {
                if ("resolving".equals(Thread.currentThread().getName())) {
                    insideCanHandle.countDown();
                    awaitQuietly(releaseCanHandle);
                }
                return super.canHandle(commandType);
            }
        };
        commandBus.addCommandHandler(firstHandler);

        var resolved  = new AtomicReference<CommandHandler>();
        var resolving = Thread.ofPlatform().name("resolving").start(() -> resolved.set(commandBus.findCommandHandlerCapableOfHandling(new CreateOrder())));
        assertThat(insideCanHandle.await(AT_MOST.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // A second handler for the same command type is added while the resolution only sees the first one
        var secondHandler = new TypeCommandHandler(CreateOrder.class);
        var registering   = Thread.ofPlatform().name("registering").start(() -> commandBus.addCommandHandler(secondHandler));
        // The registration waits for the resolution in flight
        awaitState(registering, Thread.State.WAITING);
        releaseCanHandle.countDown();
        resolving.join(AT_MOST.toMillis());
        registering.join(AT_MOST.toMillis());

        assertThat(resolved.get()).isSameAs(firstHandler);
        assertThat(commandBus.getCommandHandlers()).containsExactly(firstHandler, secondHandler);
        // Not the firstHandler the resolution in flight found
        assertThatThrownBy(() -> commandBus.findCommandHandlerCapableOfHandling(new CreateOrder()))
                .isInstanceOf(MultipleCommandHandlersFoundException.class);
    }

    @Test
    void the_handler_list_is_an_immutable_snapshot_in_registration_order() {
        var first  = new TypeCommandHandler(CreateOrder.class);
        var second = new TypeCommandHandler(Unhandled.class);
        commandBus.addCommandHandler(first);
        commandBus.addCommandHandler(second);
        commandBus.addCommandHandler(first);

        var snapshot = commandBus.getCommandHandlers();
        assertThat(snapshot).containsExactly(first, second);
        assertThatThrownBy(() -> snapshot.add(first)).isInstanceOf(UnsupportedOperationException.class);

        commandBus.removeCommandHandler(first);
        assertThat(snapshot).containsExactly(first, second);
        assertThat(commandBus.getCommandHandlers()).containsExactly(second);
        assertThat(commandBus.findCommandHandlersCapableOfHandling(CreateOrder.class)).isEmpty();
        assertThatThrownBy(() -> commandBus.send(new CreateOrder())).isInstanceOf(NoCommandHandlerFoundException.class);
    }

    private int sendUntilStopped(CountDownLatch start, AtomicBoolean sending, CommandHandler expected) {
        awaitQuietly(start);
        var resolutions = 0;
        while (sending.get()) {
            assertThat(commandBus.findCommandHandlerCapableOfHandling(new CreateOrder())).isSameAs(expected);
            resolutions++;
        }
        return resolutions;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(AT_MOST.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + latch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitState(Thread thread, Thread.State state) throws InterruptedException {
        var deadline = System.nanoTime() + AT_MOST.toNanos();
        while (thread.getState() != state && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).as("state of thread '%s'", thread.getName()).isEqualTo(state);
    }

    record CreateOrder() {
    }

    record Unhandled() {
    }

    private static class TypeCommandHandler implements CommandHandler {
        private final Class<?> commandType;

        TypeCommandHandler(Class<?> commandType) {
            this.commandType = commandType;
        }

        @Override
        public boolean canHandle(Class<?> commandType) {
            return this.commandType.isAssignableFrom(commandType);
        }

        @Override
        public Object handle(Object command) {
            return null;
        }
    }
}
