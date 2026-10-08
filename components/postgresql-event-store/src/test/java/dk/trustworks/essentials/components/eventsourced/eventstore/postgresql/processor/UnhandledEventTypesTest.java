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
        unhandledEventTypes = unhandledEventTypesFor(new OrderProjector(), UnhandledEventTypes.Reporting.WARN);

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

    /**
     * Skipping events is normal for an EventProcessor and an InTransactionEventProcessor - only a ViewEventProcessor
     * (Reporting.WARN) warns; the others count and log at DEBUG
     */
    @Test
    void a_processor_reporting_at_debug_counts_an_unhandled_event_but_never_warns() {
        var debugReporting = unhandledEventTypesFor(new OrderProjector(), UnhandledEventTypes.Reporting.DEBUG);
        logger.setLevel(Level.DEBUG);
        try {
            assertThat(debugReporting.reportIfUnhandled(event(new OrderShipped()))).isFalse();
            assertThat(debugReporting.reportIfUnhandled(event(new OrderShipped()))).isFalse();
        } finally {
            logger.setLevel(null);
        }

        assertThat(unhandledCounter(OrderShipped.class)).isEqualTo(2);
        assertThat(warnings()).isEmpty();
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                                    .extracting(ILoggingEvent::getFormattedMessage)
                                    .anySatisfy(message -> assertThat(message).contains("[OrderProjector] Ignored a '" + EventType.of(OrderShipped.class) + "' event"));
    }

    @Test
    void an_event_type_listed_in_IgnoresEventTypes_is_counted_but_not_warned_about() {
        var view = unhandledEventTypesFor(new OrderViewIgnoringShipments(), UnhandledEventTypes.Reporting.WARN);

        assertThat(view.reportIfUnhandled(event(new OrderShipped()))).isFalse();
        assertThat(view.reportIfUnhandled(event(new ExpressShipment()))).as("a subtype of a listed type").isFalse();
        assertThat(view.reportIfUnhandled(namedEvent("LegacyOrderArchived"))).isFalse();

        assertThat(unhandledCounter(OrderShipped.class)).isEqualTo(1);
        assertThat(unhandledCounter(ExpressShipment.class)).isEqualTo(1);
        assertThat(warnings()).isEmpty();

        view.reportIfUnhandled(event(new OrderCancelled()));
        view.reportIfUnhandled(namedEvent("SomeOtherName"));
        assertThat(warnings()).hasSize(2);
    }

    @Test
    void the_view_summary_lists_the_types_a_sealed_event_type_permits_that_the_view_neither_handles_nor_ignores() {
        var summary = UnhandledEventTypes.summarizeView(new SealedOrderView(), new PatternMatchingMessageHandler(new SealedOrderView()));

        assertThat(summary.handledTypes()).containsExactly("Placed");
        assertThat(summary.deliberatelyIgnored()).containsExactly("Archived");
        assertThat(summary.sealedTypesChecked()).containsExactly(SealedOrderEvent.class.getName());
        // Nested sealed types are walked; Archived is ignored on purpose; Placed is handled
        assertThat(summary.unhandledTypes()).containsExactly(LineAdded.class, LineRemoved.class, Shipped.class);
    }

    @Test
    void a_handler_for_the_sealed_supertype_leaves_nothing_unhandled() {
        var summary = UnhandledEventTypes.summarizeView(new CatchAllOrderView(), new PatternMatchingMessageHandler(new CatchAllOrderView()));

        assertThat(summary.unhandledTypes()).isEmpty();
    }

    @Test
    void a_view_handling_no_sealed_event_type_has_nothing_to_check() {
        var summary = UnhandledEventTypes.summarizeView(new OrderProjector(), new PatternMatchingMessageHandler(new OrderProjector()));

        assertThat(summary.sealedTypesChecked()).isEmpty();
        assertThat(summary.unhandledTypes()).isEmpty();
    }

    @Test
    void the_view_summary_warns_once_at_start_about_the_unhandled_sealed_event_types() {
        UnhandledEventTypes.logViewSummary("SealedOrderView", new SealedOrderView(), new PatternMatchingMessageHandler(new SealedOrderView()));

        assertThat(warnings()).singleElement().asString()
                              .startsWith("[SealedOrderView] The sealed event type(s) [" + SealedOrderEvent.class.getName() + "] permit 3 type(s)")
                              .contains(Shipped.class.getName())
                              .contains("@IgnoresEventTypes");
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.INFO)
                                    .extracting(ILoggingEvent::getFormattedMessage)
                                    .singleElement().asString()
                                    .contains("handles these event/message types: [Placed]")
                                    .contains("ignores these on purpose (@IgnoresEventTypes): [Archived]");
    }

    private UnhandledEventTypes unhandledEventTypesFor(Object processor, UnhandledEventTypes.Reporting reporting) {
        return new UnhandledEventTypes("OrderProjector", eventStore, processor, new PatternMatchingMessageHandler(processor), reporting, clock);
    }

    private PersistedEvent namedEvent(String eventName) {
        var event = mock(PersistedEvent.class);
        when(event.aggregateType()).thenReturn(ORDERS);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(nextGlobalEventOrder++));
        when(event.event()).thenReturn(new EventJSON(EssentialsJSONEventSerializers.create(), EventName.of(eventName), "{}"));
        return event;
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

    @IgnoresEventTypes(value = OrderShipped.class, eventNames = "LegacyOrderArchived")
    public static class OrderViewIgnoringShipments {
        @MessageHandler
        void on(OrderPlaced event) {
        }
    }

    public record OrderPlaced() {
    }

    public static class ExpressShipment extends OrderShipped {
    }

    public sealed interface SealedOrderEvent permits Placed, Shipped, Archived, OrderLineEvent {
    }

    public record Placed() implements SealedOrderEvent {
    }

    public record Shipped() implements SealedOrderEvent {
    }

    public record Archived() implements SealedOrderEvent {
    }

    public sealed interface OrderLineEvent extends SealedOrderEvent permits LineAdded, LineRemoved {
    }

    public record LineAdded() implements OrderLineEvent {
    }

    public record LineRemoved() implements OrderLineEvent {
    }

    @IgnoresEventTypes(Archived.class)
    public static class SealedOrderView {
        @MessageHandler
        void on(Placed event) {
        }
    }

    public static class CatchAllOrderView {
        @MessageHandler
        void on(SealedOrderEvent event) {
        }
    }

    public static class OrderShipped {
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
