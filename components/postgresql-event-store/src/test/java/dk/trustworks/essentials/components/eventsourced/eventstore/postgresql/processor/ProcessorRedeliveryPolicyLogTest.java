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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventProcessor#logRedeliveryPolicy}: the start line names the derived failover default only when the
 * policy in use is that default - the same delays <em>and</em> the same {@link MessageDeliveryErrorHandler}. A policy that
 * keeps the derived delays but classifies errors with its own handler is logged as the processor's own, handler included.
 */
class ProcessorRedeliveryPolicyLogTest {
    private static final String PROCESSOR_NAME = "ThingsEventProcessor";

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        processorLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        processorLogger().detachAppender(logAppender);
    }

    @Test
    void the_derived_default_is_logged_as_the_default() {
        var derivedDefault = FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty());
        // A fresh build of the same default, as getDurableQueueRedeliveryPolicy() returns it
        var inUse = FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty()).policy();

        new ThingsEventProcessor().logRedeliveryPolicy(PROCESSOR_NAME, inUse, derivedDefault);

        assertThat(redeliveryPolicyLines()).singleElement()
                                           .satisfies(line -> assertThat(line).contains(derivedDefault.describe())
                                                                              .doesNotContain("Uses its own RedeliveryPolicy"));
    }

    @Test
    void the_derived_delays_with_an_own_error_handler_are_logged_as_the_processors_own_policy() {
        var derivedDefault = FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty());
        var inUse = withDelaysOf(derivedDefault.policy(),
                                 MessageDeliveryErrorHandler.builder()
                                                            .alwaysRetryOn(IllegalArgumentException.class)
                                                            .build());
        // The delays alone match - RedeliveryPolicy.equals leaves the handler out
        assertThat(inUse).isEqualTo(derivedDefault.policy());

        new ThingsEventProcessor().logRedeliveryPolicy(PROCESSOR_NAME, inUse, derivedDefault);

        assertThat(redeliveryPolicyLines()).singleElement()
                                           .satisfies(line -> assertThat(line).contains("Uses its own RedeliveryPolicy")
                                                                              .contains("alwaysRetryOnExceptions=[class java.lang.IllegalArgumentException]")
                                                                              .doesNotContain(derivedDefault.describe()));
    }

    @Test
    void the_derived_delays_with_a_different_stateless_handler_are_logged_as_the_processors_own_policy() {
        var derivedDefault = FailoverRedeliveryPolicy.outlasting(Optional.empty(), Optional.empty());
        var inUse = withDelaysOf(derivedDefault.policy(), new MessageDeliveryErrorHandler.NeverRetry());

        new ThingsEventProcessor().logRedeliveryPolicy(PROCESSOR_NAME, inUse, derivedDefault);

        assertThat(redeliveryPolicyLines()).singleElement()
                                           .satisfies(line -> assertThat(line).contains("Uses its own RedeliveryPolicy")
                                                                              .contains("NeverRetry"));
    }

    private static RedeliveryPolicy withDelaysOf(RedeliveryPolicy delays, MessageDeliveryErrorHandler deliveryErrorHandler) {
        return RedeliveryPolicy.exponentialBackoff()
                               .setInitialRedeliveryDelay(delays.getInitialRedeliveryDelay())
                               .setFollowupRedeliveryDelay(delays.getFollowupRedeliveryDelay())
                               .setFollowupRedeliveryDelayMultiplier(delays.getFollowupRedeliveryDelayMultiplier())
                               .setMaximumFollowupRedeliveryDelayThreshold(delays.getMaximumFollowupRedeliveryThreshold())
                               .setMaximumNumberOfRedeliveries(delays.getMaximumNumberOfRedeliveries())
                               .setDeliveryErrorHandler(deliveryErrorHandler)
                               .build();
    }

    private List<String> redeliveryPolicyLines() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.INFO)
                               .map(ILoggingEvent::getFormattedMessage)
                               .filter(message -> message.startsWith("[" + PROCESSOR_NAME + "] "))
                               .toList();
    }

    private static Logger processorLogger() {
        return (Logger) LoggerFactory.getLogger(ThingsEventProcessor.class);
    }

    private static EventStoreSubscriptionManager subscriptionManager() {
        var eventStore = mock(EventStore.class);
        doReturn(mock(EventStoreUnitOfWorkFactory.class)).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        return subscriptionManager;
    }

    // -------------------------------------------------------------------------------------------------------------------

    static class ThingsEventProcessor extends EventProcessor {
        ThingsEventProcessor() {
            super(subscriptionManager(), mock(Inboxes.class), mock(DurableLocalCommandBus.class));
        }

        @Override
        public String getProcessorName() {
            return PROCESSOR_NAME;
        }

        @Override
        protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
            return List.of(AggregateType.of("Things"));
        }
    }
}
