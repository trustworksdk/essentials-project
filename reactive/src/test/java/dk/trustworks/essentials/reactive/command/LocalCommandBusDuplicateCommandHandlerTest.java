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
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Two {@link CommandHandler}s handling one command type used to surface only at the first send of it, as a
 * {@link MultipleCommandHandlersFoundException}. The bus now WARNs when the second one is added - and only WARNs, as an
 * overlap that is never sent keeps working.
 */
class LocalCommandBusDuplicateCommandHandlerTest {
    private LocalCommandBus                commandBus;
    private ListAppender<ILoggingEvent>    logAppender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        commandBus = new LocalCommandBus();
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LocalCommandBus.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void an_annotated_command_handler_declares_the_parameter_types_of_its_handler_methods() {
        assertThat(new OrderCommandHandler().handledCommandTypes()).containsExactlyInAnyOrder(CreateOrder.class, ConfirmOrder.class);
        assertThat(new AnnotatedCommandHandler(new OrderCommandMethods()).handledCommandTypes()).containsExactly(OrderCommand.class);
    }

    @Test
    void a_second_annotated_command_handler_for_an_already_handled_command_type_is_warned_about_when_added() {
        var first = new OrderCommandHandler();
        commandBus.addCommandHandler(first);
        assertThat(warnings()).isEmpty();

        var second = new CreateOrderCommandHandler();
        assertThatCode(() -> commandBus.addCommandHandler(second)).doesNotThrowAnyException();

        assertThat(commandBus.getCommandHandlers()).containsExactly(first, second);
        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains("2 command handlers")
                              // An AnnotatedCommandHandler's toString lists the command types it has resolved so far
                              .contains("[OrderCommandHandler::AnnotatedCommandHandler")
                              .contains(", CreateOrderCommandHandler::AnnotatedCommandHandler")
                              .contains(MultipleCommandHandlersFoundException.class.getSimpleName());
        assertThat(commandBus.findCommandTypesHandledByMoreThanOneCommandHandler(second)).containsOnlyKeys(CreateOrder.class)
                                                                                         .containsEntry(CreateOrder.class, List.of(first, second));

        // The send still fails
        assertThatThrownBy(() -> commandBus.send(new CreateOrder("o-1"))).isInstanceOf(MultipleCommandHandlersFoundException.class);
        assertThat(commandBus.<Object, ConfirmOrder>send(new ConfirmOrder("o-1"))).isEqualTo("confirmed");
    }

    @Test
    void a_handler_method_for_a_super_type_overlaps_with_one_for_a_concrete_command_type() {
        commandBus.addCommandHandler(new AnnotatedCommandHandler(new OrderCommandMethods()));
        commandBus.addCommandHandler(new OrderCommandHandler());

        assertThat(warnings()).hasSize(2)
                              .anySatisfy(warning -> assertThat(warning).contains("'" + CreateOrder.class.getName() + "'"))
                              .anySatisfy(warning -> assertThat(warning).contains("'" + ConfirmOrder.class.getName() + "'"));
    }

    @Test
    void a_command_handler_that_declares_nothing_is_checked_against_the_types_the_others_declare() {
        commandBus.addCommandHandler(new OrderCommandHandler());
        var undeclared = new UndeclaredCreateOrderCommandHandler();
        commandBus.addCommandHandler(undeclared);

        assertThat(warnings()).singleElement().asString()
                              .contains("'" + CreateOrder.class.getName() + "'")
                              .contains(undeclared.toString());
    }

    @Test
    void command_handlers_with_disjoint_command_types_are_not_warned_about() {
        commandBus.addCommandHandler(new CreateOrderCommandHandler());
        commandBus.addCommandHandler(new AnnotatedCommandHandler(new ConfirmOrderMethods()));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void a_removed_command_handler_no_longer_counts() {
        var first = new OrderCommandHandler();
        commandBus.addCommandHandler(first);
        commandBus.removeCommandHandler(first);

        commandBus.addCommandHandler(new CreateOrderCommandHandler());

        assertThat(warnings()).isEmpty();
    }

    @Test
    void the_overlaps_a_handler_would_cause_can_be_asked_for_before_it_is_registered() {
        var first = new OrderCommandHandler();
        commandBus.addCommandHandler(first);
        var candidate = new CreateOrderCommandHandler();

        assertThat(commandBus.findCommandTypesHandledByMoreThanOneCommandHandler(candidate)).containsOnlyKeys(CreateOrder.class)
                                                                                            .containsEntry(CreateOrder.class, List.of(first, candidate));
        assertThat(commandBus.findCommandHandlersCapableOfHandling(CreateOrder.class)).containsExactly(first);
        assertThat(commandBus.hasCommandHandler(candidate)).isFalse();
    }

    @Test
    void a_failing_check_is_logged_at_error_and_never_fails_the_registration() {
        commandBus.addCommandHandler(new OrderCommandHandler());
        var failing = new UndeclaredCreateOrderCommandHandler() {
            @Override
            public boolean canHandle(Class<?> commandType) {
                throw new IllegalStateException("On purpose");
            }
        };

        assertThatCode(() -> commandBus.addCommandHandler(failing)).doesNotThrowAnyException();

        assertThat(commandBus.hasCommandHandler(failing)).isTrue();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                                    .singleElement()
                                    .satisfies(event -> {
                                        assertThat(event.getFormattedMessage()).contains("Could not check whether the command types");
                                        assertThat(event.getThrowableProxy().getMessage()).isEqualTo("On purpose");
                                    });
    }

    @Test
    void a_command_bus_that_does_not_list_its_handlers_lists_none_by_default() {
        CommandBus notListing = new AbstractCommandBus(List.of()) {
            @Override
            public List<CommandHandler> getCommandHandlers() {
                // What CommandBus' default returns
                return List.of();
            }

            @Override
            public <C> void sendAndDontWait(C command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <C> void sendAndDontWait(C command, java.time.Duration delayMessageDelivery) {
                throw new UnsupportedOperationException();
            }
        };
        notListing.addCommandHandler(new OrderCommandHandler());
        var candidate = new CreateOrderCommandHandler();

        assertThat(notListing.findCommandHandlersCapableOfHandling(CreateOrder.class)).isEmpty();
        assertThat(notListing.findCommandTypesHandledByMoreThanOneCommandHandler(candidate)).isEmpty();
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }

    sealed interface OrderCommand permits CreateOrder, ConfirmOrder {
        String orderId();
    }

    record CreateOrder(String orderId) implements OrderCommand {
    }

    record ConfirmOrder(String orderId) implements OrderCommand {
    }

    static class OrderCommandHandler extends AnnotatedCommandHandler {
        @CmdHandler
        String handle(CreateOrder command) {
            return "created";
        }

        @CmdHandler
        String handle(ConfirmOrder command) {
            return "confirmed";
        }
    }

    static class CreateOrderCommandHandler extends AnnotatedCommandHandler {
        @CmdHandler
        String handle(CreateOrder command) {
            return "created-again";
        }
    }

    static class OrderCommandMethods {
        @CmdHandler
        String handle(OrderCommand command) {
            return "any";
        }
    }

    static class ConfirmOrderMethods {
        @CmdHandler
        String handle(ConfirmOrder command) {
            return "confirmed";
        }
    }

    /**
     * Declares no command types - {@link CommandHandler#handledCommandTypes()}' default
     */
    static class UndeclaredCreateOrderCommandHandler implements CommandHandler {
        @Override
        public boolean canHandle(Class<?> commandType) {
            return CreateOrder.class.isAssignableFrom(commandType);
        }

        @Override
        public Object handle(Object command) {
            return null;
        }

        @Override
        public String toString() {
            return "UndeclaredCreateOrderCommandHandler";
        }
    }
}
