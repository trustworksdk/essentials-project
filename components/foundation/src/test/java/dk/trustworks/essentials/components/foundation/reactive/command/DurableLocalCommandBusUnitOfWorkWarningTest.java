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

package dk.trustworks.essentials.components.foundation.reactive.command;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.messaging.queue.DurableQueues;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.reactive.command.SendAndDontWaitErrorHandler;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A {@link DurableLocalCommandBus} whose {@link SendAndDontWaitErrorHandler} does not rethrow, and that has no
 * {@link UnitOfWorkControllingCommandBusInterceptor}, commits a failed {@code sendAndDontWait} handler's partial writes:
 * on PostgreSQL they join the queue consumer's own {@link UnitOfWork}, and nothing marks it rollback-only. Both Spring
 * Boot starters always add the interceptor, so only a hand-built bus can end up like this - and it is told so at
 * {@link DurableLocalCommandBus#start()}.
 */
class DurableLocalCommandBusUnitOfWorkWarningTest {
    private Logger                      logger;
    private ListAppender<ILoggingEvent> appender;
    private DurableLocalCommandBus      commandBus;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(DurableLocalCommandBus.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        if (commandBus != null) {
            commandBus.stop();
        }
        logger.detachAppender(appender);
    }

    @Test
    void a_non_rethrowing_error_handler_without_a_UnitOfWork_interceptor_is_warned_about_at_start() {
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(mock(DurableQueues.class))
                                           .setSendAndDontWaitErrorHandler(new SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler())
                                           .build();

        commandBus.start();

        assertThat(warnings())
                .singleElement()
                .satisfies(warning -> assertThat(warning)
                        .contains(UnitOfWorkControllingCommandBusInterceptor.class.getSimpleName())
                        .contains(SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler.class.getSimpleName())
                        .contains("commit"));
    }

    @Test
    void the_warning_is_logged_once_even_if_the_bus_is_restarted() {
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(mock(DurableQueues.class))
                                           .setSendAndDontWaitErrorHandler(new SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler())
                                           .build();

        commandBus.start();
        commandBus.stop();
        commandBus.start();

        assertThat(warnings()).hasSize(1);
    }

    @Test
    void a_UnitOfWork_interceptor_added_after_construction_but_before_start_prevents_the_warning() {
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(mock(DurableQueues.class))
                                           .setSendAndDontWaitErrorHandler(new SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler())
                                           .build();
        commandBus.addInterceptor(new UnitOfWorkControllingCommandBusInterceptor(mock(UnitOfWorkFactory.class)));

        commandBus.start();

        assertThat(warnings()).isEmpty();
    }

    @Test
    void the_default_rethrowing_error_handler_without_a_UnitOfWork_interceptor_is_not_warned_about() {
        commandBus = new DurableLocalCommandBus(mock(DurableQueues.class));

        commandBus.start();

        assertThat(warnings()).isEmpty();
    }

    /**
     * The shape both Spring Boot starters build their {@code essentialsCommandBus} bean in: the builder's error handler
     * (or the application's) plus a {@link UnitOfWorkControllingCommandBusInterceptor}.
     */
    @Test
    void the_starters_default_bean_shape_is_not_warned_about_even_with_a_non_rethrowing_error_handler() {
        commandBus = DurableLocalCommandBus.builder()
                                           .setDurableQueues(mock(DurableQueues.class))
                                           .setSendAndDontWaitErrorHandler(new SendAndDontWaitErrorHandler.FallbackSendAndDontWaitErrorHandler())
                                           .addInterceptors(List.of(new UnitOfWorkControllingCommandBusInterceptor(mock(UnitOfWorkFactory.class))))
                                           .build();

        commandBus.start();

        assertThat(warnings()).isEmpty();
    }

    private List<String> warnings() {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList();
    }
}
