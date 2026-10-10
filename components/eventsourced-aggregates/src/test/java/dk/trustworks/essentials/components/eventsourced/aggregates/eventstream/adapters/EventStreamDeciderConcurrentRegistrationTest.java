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

package dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfiguratorTest.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import dk.trustworks.essentials.reactive.command.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Review F-004: {@link EventStreamDeciderAndAggregateTypeConfigurator} keeps the adapters registered per {@link CommandBus}
 * in a plain {@link ArrayList}, which the duplicate-registration diagnostic iterates and {@code removeIf}s under the map's
 * monitor. Registration used to append to it outside that monitor, so a configurator registering on one thread while
 * another configurator on the same {@link CommandBus} ran the diagnostic on another made that {@code removeIf} throw a
 * {@link ConcurrentModificationException} - swallowed at DEBUG, so the duplicate WARN silently went missing.
 * <p>
 * The window is a few instructions wide, so the test forces the interleaving instead of hoping for it. It seeds the
 * configurator's per-bus map with a list that pauses the registering configurator's append until the reading
 * configurator is inside its {@code removeIf} (whose {@link CommandBus#hasCommandHandler(CommandHandler)} calls then wait
 * for the append to finish). An append made under the monitor never gets that far: the reader is shut out, blocked on
 * the monitor the registering thread holds, and the pause ends as soon as the test sees that.
 * <p>
 * White-box by necessity - nothing else reaches between the two. Should the seeded field be renamed, the test fails
 * loudly rather than passing without testing anything.
 */
class EventStreamDeciderConcurrentRegistrationTest {
    private static final String   REGISTERING_THREAD = "registering-configurator";
    private static final String   READING_THREAD     = "reading-configurator";
    private static final Duration AT_MOST            = Duration.ofSeconds(10);

    private ConfigurableEventStore<?>                                      eventStore;
    private Map<CommandBus, List<EventStreamDeciderCommandHandlerAdapter>> adaptersRegisteredPerCommandBus;
    private ListAppender<ILoggingEvent>                                    logAppender;
    private ch.qos.logback.classic.Logger                                  logger;
    private Level                                                          originalLevel;

    private final    CountDownLatch registeringIsAboutToAppend = new CountDownLatch(1);
    private final    CountDownLatch readingIsInsideRemoveIf    = new CountDownLatch(1);
    private final    CountDownLatch registeringHasAppended     = new CountDownLatch(1);
    private volatile Thread         readingThread;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws ReflectiveOperationException {
        eventStore = mock(ConfigurableEventStore.class);

        var field = EventStreamDeciderAndAggregateTypeConfigurator.class.getDeclaredField("ADAPTERS_REGISTERED_PER_COMMAND_BUS");
        field.setAccessible(true);
        adaptersRegisteredPerCommandBus = (Map<CommandBus, List<EventStreamDeciderCommandHandlerAdapter>>) field.get(null);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EventStreamDeciderAndAggregateTypeConfigurator.class);
        originalLevel = logger.getLevel();
        // DEBUG: the diagnostic logs the ConcurrentModificationException it catches at DEBUG
        logger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logger.setLevel(originalLevel);
    }

    @Test
    @Timeout(30)
    void a_registration_on_one_thread_never_lands_inside_another_configurators_diagnostic_on_the_same_command_bus() throws Exception {
        var commandBus = threadSafeCommandBus();
        adaptersRegisteredPerCommandBus.put(commandBus, new PausingAdapterList());
        // Every configurator handles CreateOrder, so each one after the first runs the diagnostic over a non-empty list and WARNs
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration("Orders-1")), List.of(new CreateOrderDecider()));

        var registering = new FutureTask<>(() -> new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration("Orders-2")), List.of(new CreateOrderDecider())));
        var reading     = new FutureTask<>(() -> new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration("Orders-3")), List.of(new CreateOrderDecider())));
        readingThread = Thread.ofPlatform().name(READING_THREAD).daemon().unstarted(reading);

        Thread.ofPlatform().name(REGISTERING_THREAD).daemon().start(registering);
        assertThat(registeringIsAboutToAppend.await(AT_MOST.toMillis(), TimeUnit.MILLISECONDS)).as("the registering configurator reached its append").isTrue();
        readingThread.start();

        // Rethrows if either construction failed outright
        registering.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
        reading.get(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(readingIsInsideRemoveIf.getCount()).as("the reading configurator iterated the per-bus adapter list").isZero();

        assertThat(diagnosticFailures()).as("the diagnostic failed on the per-bus adapter list").isEmpty();

        // Both concurrent registrations count: one more CreateOrder adapter is the fourth
        logAppender.list.clear();
        new EventStreamDeciderAndAggregateTypeConfigurator(eventStore, commandBus, List.of(orderConfiguration("Orders-4")), List.of(new CreateOrderDecider()));
        assertThat(warnings()).as("a registration was lost")
                              .singleElement().asString()
                              .contains("would be handled by 4 command handlers");
    }

    /**
     * Pauses the registering configurator's append until the reading configurator is inside its {@code removeIf} - or is
     * shut out of it by the map's monitor, which only an append made under that monitor does
     */
    private final class PausingAdapterList extends ArrayList<EventStreamDeciderCommandHandlerAdapter> {
        @Override
        public boolean add(EventStreamDeciderCommandHandlerAdapter adapter) {
            if (!REGISTERING_THREAD.equals(Thread.currentThread().getName())) {
                return super.add(adapter);
            }
            registeringIsAboutToAppend.countDown();
            var deadline = System.nanoTime() + AT_MOST.toNanos();
            try {
                while (!readingIsInsideRemoveIf.await(1, TimeUnit.MILLISECONDS) && !readingIsBlockedOnTheAdapterMap() && System.nanoTime() < deadline) {
                    // Wait for the reader to get in, or to be shut out
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var added = super.add(adapter);
            registeringHasAppended.countDown();
            return added;
        }
    }

    private boolean readingIsBlockedOnTheAdapterMap() {
        var reader = readingThread;
        if (reader == null || reader.getState() != Thread.State.BLOCKED) {
            return false;
        }
        var threadInfo = ManagementFactory.getThreadMXBean().getThreadInfo(reader.threadId());
        return threadInfo != null && threadInfo.getLockInfo() != null &&
                threadInfo.getLockInfo().getIdentityHashCode() == System.identityHashCode(adaptersRegisteredPerCommandBus);
    }

    private static EventStreamAggregateTypeConfiguration orderConfiguration(String aggregateType) {
        return new EventStreamAggregateTypeConfiguration(AggregateType.of(aggregateType),
                                                         OrderId.class,
                                                         AggregateIdSerializer.serializerFor(OrderId.class),
                                                         new EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType(OrderCommand.class),
                                                         command -> ((OrderCommand) command).orderId(),
                                                         event -> ((OrderEvent) event).orderId());
    }

    /**
     * A {@link CommandBus} that is itself safe to use concurrently - {@link LocalCommandBus} keeps its handlers in a plain
     * {@link HashSet}. {@link CommandBus#hasCommandHandler(CommandHandler)} is only called from the configurator's
     * {@code removeIf}: there the reading thread signals that it is inside, then waits for the registering thread's append
     */
    private CommandBus threadSafeCommandBus() {
        var handlers   = ConcurrentHashMap.<CommandHandler>newKeySet();
        var commandBus = mock(CommandBus.class);
        when(commandBus.addCommandHandler(any())).thenAnswer(invocation -> {
            handlers.add(invocation.getArgument(0));
            return commandBus;
        });
        when(commandBus.hasCommandHandler(any())).thenAnswer(invocation -> {
            if (READING_THREAD.equals(Thread.currentThread().getName()) && readingIsInsideRemoveIf.getCount() > 0) {
                readingIsInsideRemoveIf.countDown();
                registeringHasAppended.await(AT_MOST.toMillis(), TimeUnit.MILLISECONDS);
            }
            return handlers.contains(invocation.<CommandHandler>getArgument(0));
        });
        return commandBus;
    }

    private List<String> diagnosticFailures() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.DEBUG && event.getThrowableProxy() != null)
                               .map(event -> event.getFormattedMessage() + ": " + event.getThrowableProxy().getClassName())
                               .toList();
    }

    private List<String> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .map(ILoggingEvent::getFormattedMessage)
                               .toList();
    }
}
