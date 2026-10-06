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

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class CommandContextPropagatorTest {
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private LocalCommandBus       commandBus;
    private BlockingQueue<String> seenByHandler;
    private List<String>          wrappingOrder;

    @BeforeEach
    void setup() {
        seenByHandler = new LinkedBlockingQueue<>();
        wrappingOrder = new CopyOnWriteArrayList<>();
        commandBus = new LocalCommandBus();
        commandBus.addCommandHandler(new CommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                return String.class.equals(commandType);
            }

            @Override
            public Object handle(Object command) {
                seenByHandler.add(Objects.requireNonNullElse(CONTEXT.get(), "<none>") + "@" + Thread.currentThread().getName());
                return "done";
            }
        });
    }

    @AfterEach
    void cleanup() {
        CONTEXT.remove();
    }

    @Test
    void the_context_is_captured_on_the_sending_thread_and_restored_on_the_handling_thread() throws Exception {
        commandBus.addContextPropagator(new ThreadLocalPropagator("only"));
        CONTEXT.set("from-sender");

        commandBus.sendAndDontWait("command");

        var seen = seenByHandler.poll(5, TimeUnit.SECONDS);
        assertThat(seen).startsWith("from-sender@")
                        .doesNotEndWith("@" + Thread.currentThread().getName());
    }

    @Test
    void sendAsync_captures_when_the_command_is_sent_not_when_it_is_subscribed() throws Exception {
        commandBus.addContextPropagator(new ThreadLocalPropagator("only"));
        CONTEXT.set("at-send");
        var mono = commandBus.sendAsync("command");
        CONTEXT.set("at-subscribe");

        mono.block(Duration.ofSeconds(5));

        assertThat(seenByHandler.poll(5, TimeUnit.SECONDS)).startsWith("at-send@");
    }

    @Test
    void propagators_added_first_wrap_outermost() throws Exception {
        commandBus.addContextPropagator(new ThreadLocalPropagator("first"));
        commandBus.addContextPropagator(new ThreadLocalPropagator("second"));

        commandBus.sendAsync("command").block(Duration.ofSeconds(5));

        assertThat(wrappingOrder).containsExactly("first", "second");
    }

    @Test
    void the_same_propagator_is_only_added_once() {
        var propagator = new ThreadLocalPropagator("only");

        commandBus.addContextPropagator(propagator);
        commandBus.addContextPropagator(propagator);

        assertThat(commandBus.getContextPropagators()).containsExactly(propagator);
    }

    private final class ThreadLocalPropagator implements CommandContextPropagator {
        private final String name;

        ThreadLocalPropagator(String name) {
            this.name = name;
        }

        @Override
        public <R> Callable<R> propagate(Callable<R> handlerInvocation) {
            var captured = CONTEXT.get();
            return () -> {
                wrappingOrder.add(name);
                var previous = CONTEXT.get();
                CONTEXT.set(captured);
                try {
                    return handlerInvocation.call();
                } finally {
                    CONTEXT.set(previous);
                }
            };
        }
    }
}
