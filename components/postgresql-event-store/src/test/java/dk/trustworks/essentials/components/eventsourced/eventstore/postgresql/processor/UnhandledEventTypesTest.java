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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.PatternMatchingMessageHandler;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CB-4: an event type a processor has no {@code @MessageHandler} for used to be dropped without a trace
 */
class UnhandledEventTypesTest {
    private static final AggregateType ORDERS = AggregateType.of("Orders");

    private SimpleMeterRegistry                meterRegistry;
    private EventStore                         eventStore;
    private MutableClock                       clock;
    private UnhandledEventTypes                unhandledEventTypes;
    private ListAppender<ILoggingEvent>        logAppender;
    private ch.qos.logback.classic.Logger      logger;
    private long                               nextGlobalEventOrder = 1;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        eventStore = mock(EventStore.class);
        when(eventStore.getEventStoreSubscriptionObserver()).thenReturn(new MeasurementEventStoreSubscriptionObserver(MeasurementTaker.none(), null, meterRegistry));
        clock = new MutableClock(Instant.parse("2026-10-08T10:00:00Z"));
        unhandledEventTypes = new UnhandledEventTypes("OrderProjector", eventStore, new PatternMatchingMessageHandler(new OrderProjector()), clock);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UnhandledEventTypes.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void the_handled_message_types_are_the_payload_types_of_the_message_handler_methods() {
        assertThat(UnhandledEventTypes.handledMessageTypes(new OrderProjector())).containsExactly("OrderPlaced");
    }

    @Test
    void an_unhandled_event_is_counted_every_time_and_warned_about_at_most_once_per_interval() {
        assertThat(unhandledEventTypes.reportIfUnhandled(event(new OrderPlaced()))).isTrue();
        assertThat(unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()))).isFalse();
        assertThat(unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()))).isFalse();
        assertThat(unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()))).isFalse();

        assertThat(unhandledCounter(OrderShipped.class)).isEqualTo(3);
        assertThat(meterRegistry.find(MeasurementEventStoreSubscriptionObserver.PROCESSOR_UNHANDLED_EVENT_TYPE_METRIC)
                                .tag("event_type", EventType.of(OrderPlaced.class).toString())
                                .counter()).isNull();
        assertThat(warnings()).singleElement().asString()
                              .contains("[OrderProjector] Ignored 1 '" + EventType.of(OrderShipped.class) + "' event(s) of AggregateType 'Orders'");

        clock.advance(UnhandledEventTypes.WARN_INTERVAL.minusSeconds(1));
        unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()));
        assertThat(warnings()).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()));
        assertThat(warnings()).hasSize(2);
        assertThat(warnings().get(1)).contains("Ignored 4 '" + EventType.of(OrderShipped.class) + "' event(s)");
        assertThat(unhandledCounter(OrderShipped.class)).isEqualTo(5);
    }

    @Test
    void each_unhandled_event_type_is_warned_about_on_its_own() {
        unhandledEventTypes.reportIfUnhandled(event(new OrderShipped()));
        unhandledEventTypes.reportIfUnhandled(event(new OrderCancelled()));

        assertThat(warnings()).hasSize(2);
    }

    private double unhandledCounter(Class<?> eventType) {
        var counter = meterRegistry.find(MeasurementEventStoreSubscriptionObserver.PROCESSOR_UNHANDLED_EVENT_TYPE_METRIC)
                                   .tag("processor", "OrderProjector")
                                   .tag("aggregate_type", ORDERS.toString())
                                   .tag("event_type", EventType.of(eventType).toString())
                                   .counter();
        return counter == null ? 0 : counter.count();
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }

    private PersistedEvent event(Object payload) {
        var event = mock(PersistedEvent.class);
        when(event.aggregateType()).thenReturn(ORDERS);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(nextGlobalEventOrder++));
        when(event.event()).thenReturn(new EventJSON(EssentialsJSONEventSerializers.create(), EventType.of(payload.getClass()), "{}"));
        return event;
    }

    public static class OrderProjector {
        @MessageHandler
        void on(OrderPlaced event) {
        }
    }

    public record OrderPlaced() {
    }

    public record OrderShipped() {
    }

    public record OrderCancelled() {
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
