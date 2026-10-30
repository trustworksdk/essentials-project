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
import dk.trustworks.essentials.reactive.Handler;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * A handler method's exception is rethrown to the sender, which reports it, so {@link AnnotatedCommandHandler} logs it
 * at DEBUG by default - at ERROR, with a stack trace, every failure was logged twice and once more per retry.
 */
class AnnotatedCommandHandlerExceptionLoggingTest {
    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent>   logAppender;
    private Level                         levelBefore;

    @BeforeEach
    void captureLog() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AnnotatedCommandHandler.class);
        levelBefore = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(logAppender);
        logger.setLevel(levelBefore);
    }

    @Test
    void a_failing_handler_method_is_rethrown_and_logged_at_debug_by_default() {
        var handler = new FailingHandler();

        assertThatThrownBy(() -> handler.handle((Object) "command")).isInstanceOf(IllegalStateException.class).hasMessage("not yet");

        assertThat(logAppender.list).filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO)).isEmpty();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                                    .singleElement()
                                    .satisfies(event -> assertThat(event.getThrowableProxy()).as("the cause is attached").isNotNull());
    }

    @Test
    void an_override_still_chooses_the_level() {
        var handler = new FailingHandler() {
            @Override
            protected Optional<org.slf4j.event.Level> getExceptionLogLevel(Exception exception) {
                return Optional.of(org.slf4j.event.Level.ERROR);
            }
        };

        assertThatThrownBy(() -> handler.handle((Object) "command")).isInstanceOf(IllegalStateException.class);

        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.ERROR).hasSize(1);
    }

    private static class FailingHandler extends AnnotatedCommandHandler {
        @Handler
        public String onCommand(String command) {
            throw new IllegalStateException("not yet");
        }
    }
}
