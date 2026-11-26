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

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventlog.processor.test_data.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.postgresql.ds.PGSimpleDataSource;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The event type filter the processor base classes subscribe with: derived from the {@link MessageHandler} payload
 * types when all are final, {@link EventTypeFilter#all()} otherwise, and replaced by an overridden
 * {@code eventTypeFilter()}. The subscriptions are built at the first start, which fails here without a database after
 * the build, so the built definitions can be read.
 */
class HandledEventTypesTest {
    private static final EventTypeFilter ADDED_ONLY = EventTypeFilter.of("FQCN:" + Added.class.getName());

    private final EventLog                      eventLog          = EventLog.builder().setDataSource(new PGSimpleDataSource()).build();
    private final EventLogProcessorDependencies dependencies      = new EventLogProcessorDependencies(eventLog, EssentialsJSONEventSerializers.create(),
                                                                                                       mock(PageTransactionSource.class));
    @SuppressWarnings("unchecked")
    private final UnitOfWorkFactory<UnitOfWork> unitOfWorkFactory = mock(UnitOfWorkFactory.class);

    record Removed(String orderId) {
    }

    enum Status {
        OPEN
    }

    static final class Plain {
    }

    static class Open {
    }

    abstract static class Abstract {
    }

    static class FinalHandlers {
        @MessageHandler
        void on(Added added, DeliveredEvent event) {
        }

        @MessageHandler
        void on(Removed removed) {
        }

        void notAHandler(Open open) {
        }
    }

    static class InheritedHandlers extends FinalHandlers {
        @MessageHandler
        void on(OrderEvent any) {
        }
    }

    @Test
    void every_final_payload_type_gives_a_filter_of_their_fqcn_names() {
        assertThat(HandledEventTypes.derive("p", FinalHandlers.class))
                .isEqualTo(EventTypeFilter.of("FQCN:" + Added.class.getName(), "FQCN:" + Removed.class.getName()));
        assertThat(HandledEventTypes.derive("p", List.of(Status.class, Plain.class)).eventTypes())
                .containsExactlyInAnyOrder("FQCN:" + Status.class.getName(), "FQCN:" + Plain.class.getName());
    }

    @Test
    void a_payload_type_that_other_classes_can_match_gives_every_event_type() {
        assertThat(HandledEventTypes.derive("p", InheritedHandlers.class)).isEqualTo(EventTypeFilter.all());
        for (var type : List.<Class<?>>of(OrderEvent.class, Open.class, Abstract.class, Object.class, String.class, Integer.class, int.class, Added[].class)) {
            assertThat(HandledEventTypes.derive("p", List.of(Added.class, type))).as(type.getName()).isEqualTo(EventTypeFilter.all());
            assertThat(HandledEventTypes.whyNotFilterable(type)).as(type.getName()).isPresent();
        }
        assertThat(HandledEventTypes.derive("p", List.of())).isEqualTo(EventTypeFilter.all());
        assertThat(HandledEventTypes.derive("p", Object.class)).isEqualTo(EventTypeFilter.all());
    }

    // ---- the four base classes ----

    class View extends EventLogViewProcessor {
        View() {
            super("view", List.of("Orders", "Invoices"), dependencies);
        }

        @MessageHandler
        void on(Added added) {
        }
    }

    class WideView extends View {
        @Override
        protected EventTypeFilter eventTypeFilter() {
            return EventTypeFilter.all();
        }
    }

    class SupertypeView extends View {
        @MessageHandler
        void on(OrderEvent event) {
        }
    }

    class Reactor extends EventLogReactor {
        Reactor() {
            super("reactor", List.of("Orders"), dependencies);
        }

        @MessageHandler
        void on(Added added, DeliveredEvent event) {
        }
    }

    class WiderReactor extends Reactor {
        @Override
        protected EventTypeFilter eventTypeFilter() {
            var names = new HashSet<>(super.eventTypeFilter().eventTypes());
            names.add("FQCN:com.example.Aliased");
            return new EventTypeFilter(names);
        }
    }

    class InboxHandlers extends EventLogInboxProcessor {
        InboxHandlers(Inboxes inboxes) {
            super("inbox", List.of("Orders"), dependencies, inboxes, unitOfWorkFactory);
        }

        @MessageHandler
        void on(Added added, OrderedMessage message) {
        }
    }

    class PartitionedView extends EventLogPartitionedViewProcessor {
        PartitionedView(DurableQueues durableQueues) {
            super("partitioned", List.of("Orders"), dependencies, durableQueues, unitOfWorkFactory);
        }

        @MessageHandler
        void on(Added added) {
        }
    }

    @Test
    void a_view_processor_subscribes_every_aggregate_type_with_its_handled_types_and_an_override_wins() {
        var view = new View();
        assertThatThrownBy(view::start).isInstanceOf(IllegalStateException.class);
        assertThat(view.projections()).hasSize(2).allSatisfy(projection -> assertThat(projection.definition().eventTypes()).isEqualTo(ADDED_ONLY));

        var wide = new WideView();
        assertThatThrownBy(wide::start).isInstanceOf(IllegalStateException.class);
        assertThat(wide.projections()).allSatisfy(projection -> assertThat(projection.definition().eventTypes().matchesAll()).isTrue());

        var supertype = new SupertypeView();
        assertThatThrownBy(supertype::start).isInstanceOf(IllegalStateException.class);
        assertThat(supertype.projections()).allSatisfy(projection -> assertThat(projection.definition().eventTypes().matchesAll()).isTrue());
    }

    @Test
    void a_reactor_subscribes_with_its_handled_types_and_an_override_can_add_names() {
        var reactor = new Reactor();
        assertThatThrownBy(reactor::start).isInstanceOf(IllegalStateException.class);
        assertThat(reactor.reactors()).singleElement().satisfies(r -> assertThat(r.definition().eventTypes()).isEqualTo(ADDED_ONLY));

        var wider = new WiderReactor();
        assertThatThrownBy(wider::start).isInstanceOf(IllegalStateException.class);
        assertThat(wider.reactors()).singleElement()
                                    .satisfies(r -> assertThat(r.definition().eventTypes().eventTypes())
                                            .containsExactlyInAnyOrder("FQCN:" + Added.class.getName(), "FQCN:com.example.Aliased"));
    }

    @Test
    void the_inbox_processors_reading_side_subscribes_with_the_owners_handled_types() {
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), ArgumentMatchers.<java.util.function.Consumer<Message>>any())).thenReturn(mock(Inbox.class));
        var processor = new InboxHandlers(inboxes);
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);
        assertThat(processor.projections()).singleElement().satisfies(p -> assertThat(p.definition().eventTypes()).isEqualTo(ADDED_ONLY));
    }

    @Test
    void the_partitioned_view_processors_reading_side_subscribes_with_the_owners_handled_types() {
        var durableQueues = mock(DurableQueues.class);
        when(durableQueues.keepsKeyOrderAcrossInstances()).thenReturn(true);
        var processor = new PartitionedView(durableQueues);
        assertThatThrownBy(processor::start).isInstanceOf(IllegalStateException.class);
        assertThat(processor.projections()).singleElement().satisfies(p -> assertThat(p.definition().eventTypes()).isEqualTo(ADDED_ONLY));
    }
}
