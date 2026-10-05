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

package dk.trustworks.essentials.components.foundation.causation;

import dk.trustworks.essentials.components.foundation.types.EventId;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a {@link CausationContext} binding reaches a command handler through the plain, non-durable
 * {@link LocalCommandBus}. Only {@code send} runs the handler on the caller's thread; {@code sendAndDontWait} and
 * {@code sendAsync} run it on a Reactor worker ({@code publishOn} fuses with {@code Mono.fromCallable} and pulls the
 * callable onto the worker), so the cause reaches the handler there only through a
 * {@link CausationCommandContextPropagator}.
 */
class CausationAcrossLocalCommandBusTest {
    private static final EventId CAUSE = EventId.of("the-cause");

    private LocalCommandBus                  commandBus;
    private BlockingQueue<Optional<EventId>> seenByHandler;

    @BeforeEach
    void setup() {
        seenByHandler = new LinkedBlockingQueue<>();
        commandBus = new LocalCommandBus();
        commandBus.addCommandHandler(new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return DoSomething.class.equals(commandType);
            }

            @Override
            public Object handle(Object command) {
                seenByHandler.add(CausationContext.current());
                return "done";
            }
        });
    }

    @Nested
    class With_the_propagator {
        @BeforeEach
        void addPropagator() {
            commandBus.addContextPropagator(new CausationCommandContextPropagator());
        }

        @Test
        void send_carries_the_cause() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.send(new DoSomething()));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void sendAndDontWait_carries_the_cause() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.sendAndDontWait(new DoSomething()));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void a_delayed_sendAndDontWait_carries_the_cause() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.sendAndDontWait(new DoSomething(), Duration.ofMillis(50)));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void sendAsync_carries_the_cause() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.sendAsync(new DoSomething()).block(Duration.ofSeconds(5)));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void sendAsync_subscribed_after_the_binding_has_ended_still_carries_the_cause_captured_at_send() throws Exception {
            var mono = CausationContext.where(CAUSE).call(() -> commandBus.sendAsync(new DoSomething()));

            mono.block(Duration.ofSeconds(5));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void a_command_sent_with_no_cause_bound_is_handled_without_one() throws Exception {
            commandBus.sendAndDontWait(new DoSomething());

            assertThat(nextSeen()).isEmpty();
        }
    }

    /**
     * Pins why the propagator exists: if the bus ever stops handing work to a Reactor worker, these fail and the
     * propagator can be reconsidered
     */
    @Nested
    class Without_the_propagator {
        @Test
        void send_still_carries_the_cause_because_the_handler_runs_on_the_callers_thread() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.send(new DoSomething()));

            assertThat(nextSeen()).contains(CAUSE);
        }

        @Test
        void sendAndDontWait_loses_the_cause_because_the_handler_runs_on_a_Reactor_worker() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.sendAndDontWait(new DoSomething()));

            assertThat(nextSeen()).isEmpty();
        }

        @Test
        void sendAsync_loses_the_cause_because_the_handler_runs_on_a_Reactor_worker() throws Exception {
            CausationContext.where(CAUSE).run(() -> commandBus.sendAsync(new DoSomething()).block(Duration.ofSeconds(5)));

            assertThat(nextSeen()).isEmpty();
        }
    }

    private Optional<EventId> nextSeen() throws InterruptedException {
        var seen = seenByHandler.poll(5, TimeUnit.SECONDS);
        assertThat(seen).as("the handler was never invoked").isNotNull();
        return seen;
    }

    record DoSomething() {
    }
}
