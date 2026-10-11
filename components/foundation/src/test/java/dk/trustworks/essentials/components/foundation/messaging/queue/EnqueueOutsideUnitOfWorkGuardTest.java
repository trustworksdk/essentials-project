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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnqueueOutsideUnitOfWorkGuardTest {
    private static final QueueName QUEUE = QueueName.of("orders");

    private Logger                      logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(DurableQueues.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void clearSpringTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        logger.detachAppender(appender);
    }

    /**
     * By package this test class is a framework class, so tests that resolve the call site say which classes play the
     * application: this test class, plus {@code applicationClasses}
     */
    private static EnqueueOutsideUnitOfWorkGuard guardWithApplicationClasses(Class<?>... applicationClasses) {
        var application = new HashSet<String>();
        application.add(EnqueueOutsideUnitOfWorkGuardTest.class.getName());
        Arrays.stream(applicationClasses).map(Class::getName).forEach(application::add);
        return new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class, className -> !application.contains(className));
    }

    @Test
    void an_enqueue_outside_a_unit_of_work_is_warned_about_once_per_call_site() {
        var guard = guardWithApplicationClasses();

        for (var i = 0; i < 2; i++) {
            enqueueFromTheFirstCallSite(guard);
        }
        enqueueFromTheSecondCallSite(guard);

        assertThat(warnings())
                .as("the same call site twice is one WARN, and a benign call site logged first must not hide another one")
                .hasSize(2)
                .satisfiesExactly(first -> assertThat(first).contains("enqueueFromTheFirstCallSite"),
                                  second -> assertThat(second).contains("enqueueFromTheSecondCallSite"));
    }

    @Test
    void the_call_site_skips_the_framework_frames_between_the_application_and_the_enqueue() {
        var guard = guardWithApplicationClasses();

        sendThroughTheOutboxFromTheFirstCallSite(guard);
        sendThroughTheOutboxFromTheSecondCallSite(guard);

        assertThat(warnings())
                .as("two application call sites reaching the enqueue through the same framework path")
                .hasSize(2)
                .satisfiesExactly(first -> assertThat(first).contains("sendThroughTheOutboxFromTheFirstCallSite").doesNotContain("FakeOutbox"),
                                  second -> assertThat(second).contains("sendThroughTheOutboxFromTheSecondCallSite").doesNotContain("FakeOutbox"));
    }

    @Test
    void callers_behind_one_application_wrapper_are_distinct_call_sites() {
        var guard = guardWithApplicationClasses(ApplicationGateway.class);

        sendThroughTheGatewayFromTheFirstCaller(guard);
        sendThroughTheGatewayFromTheSecondCaller(guard);

        assertThat(warnings())
                .as("the wrapper is the innermost application frame for both, so the key must reach past it")
                .hasSize(2)
                .satisfiesExactly(first -> assertThat(first).contains("ApplicationGateway.send").contains("sendThroughTheGatewayFromTheFirstCaller"),
                                  second -> assertThat(second).contains("ApplicationGateway.send").contains("sendThroughTheGatewayFromTheSecondCaller"));
    }

    @Test
    void with_no_application_frame_the_call_site_is_the_first_frame_outside_the_guard_and_the_durable_queues() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class, className -> true);

        FakeOutbox.send(guard);
        FakeOutbox.send(guard);

        assertThat(warnings())
                .as("an enqueue the framework makes on its own")
                .singleElement()
                .satisfies(warning -> assertThat(warning).contains(FakeOutbox.class.getName() + ".send(").doesNotContain(" <- "));
    }

    @Test
    void the_default_call_site_resolution_treats_the_framework_jdk_and_spring_as_framework() {
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Outbox")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("java.lang.Thread")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("org.springframework.transaction.support.TransactionTemplate")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("reactor.core.publisher.Mono")).isTrue();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isFrameworkClassByDefault("com.acme.orders.OrderService")).isFalse();
    }

    @Test
    void an_enqueue_inside_a_unit_of_work_is_not_warned_about() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);

        guard.checkEnqueue(QUEUE, factoryWith(mock(UnitOfWork.class)));

        assertThat(warnings()).isEmpty();
    }

    @Test
    void after_the_maximum_number_of_call_sites_one_line_says_further_ones_are_not_logged() {
        var guard   = guardWithApplicationClasses();
        var sites   = EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALL_SITES + 5;
        assertThat(sites).as("enqueueFromCallSite tells apart " + DISTINCT_INNERMOST_FRAMES + " innermost frames").isLessThanOrEqualTo(DISTINCT_INNERMOST_FRAMES);

        for (var site = 0; site < sites; site++) {
            enqueueFromCallSite(guard, site);
        }
        for (var site = 0; site < sites; site++) {
            enqueueFromCallSite(guard, site);
        }

        var warnings = warnings();
        assertThat(warnings).hasSize(EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALL_SITES + 1);
        assertThat(warnings.subList(0, EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALL_SITES))
                .as("each a distinct call site")
                .doesNotHaveDuplicates()
                .allSatisfy(warning -> assertThat(warning).contains("enqueueFromCallSite"));
        assertThat(warnings.getLast()).contains("further call sites are not logged");
    }

    @Test
    void one_wrapper_reached_from_many_callers_does_not_use_up_the_cap() {
        var guard   = guardWithApplicationClasses(ApplicationGateway.class);
        var callers = EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALL_SITES + 8;
        assertThat(callers).as("sendThroughTheGatewayFromCaller tells apart 8^2 callers").isLessThanOrEqualTo(64);

        for (var caller = 0; caller < callers; caller++) {
            sendThroughTheGatewayFromCaller(guard, caller, 2);
        }
        enqueueFromCallSite(guard, 0);

        var warnings = warnings();
        assertThat(warnings)
                .as("the wrapper's first caller variants, then the other call site - not a 'further call sites' line")
                .hasSize(EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALLERS_PER_INNERMOST_FRAME + 1)
                .noneSatisfy(warning -> assertThat(warning).contains("further call sites are not logged"));
        assertThat(warnings.subList(0, EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALLERS_PER_INNERMOST_FRAME))
                .doesNotHaveDuplicates()
                .allSatisfy(warning -> assertThat(warning).contains("ApplicationGateway.send").contains("sendThroughTheGatewayFromCaller"));
        assertThat(warnings.getLast()).contains("enqueueFromCallSite").doesNotContain("ApplicationGateway");
    }

    @RepeatedTest(40)
    void the_cap_holds_when_threads_race_for_the_last_slot() throws InterruptedException {
        var guard = guardWithApplicationClasses();
        var max   = EnqueueOutsideUnitOfWorkGuard.MAX_WARNED_CALL_SITES;
        for (var site = 0; site < max - 1; site++) {
            enqueueFromCallSite(guard, site);
        }
        var racers = 8;
        assertThat(max - 1 + racers).isLessThanOrEqualTo(DISTINCT_INNERMOST_FRAMES);
        var start    = new CountDownLatch(1);
        var finished = new CountDownLatch(racers);
        var failures = new CopyOnWriteArrayList<Throwable>();
        for (var racer = 0; racer < racers; racer++) {
            var site = max - 1 + racer;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    enqueueFromCallSite(guard, site);
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).isEmpty();

        var warnings = warnings();
        assertThat(warnings.stream().filter(warning -> warning.contains("further call sites are not logged")))
                .as("exactly one 'further call sites' line")
                .hasSize(1);
        assertThat(warnings.stream().filter(warning -> warning.contains("enqueueFromCallSite")))
                .as("exactly one racer wins the last slot")
                .hasSize(max)
                .doesNotHaveDuplicates();
        assertThat(warnings).hasSize(max + 1);
    }

    private static void enqueueFromTheFirstCallSite(EnqueueOutsideUnitOfWorkGuard guard) {
        guard.checkEnqueue(QUEUE, factoryWith(null));
    }

    private static void enqueueFromTheSecondCallSite(EnqueueOutsideUnitOfWorkGuard guard) {
        guard.checkEnqueue(QUEUE, factoryWith(null));
    }

    private static void sendThroughTheOutboxFromTheFirstCallSite(EnqueueOutsideUnitOfWorkGuard guard) {
        FakeOutbox.send(guard);
    }

    private static void sendThroughTheOutboxFromTheSecondCallSite(EnqueueOutsideUnitOfWorkGuard guard) {
        FakeOutbox.send(guard);
    }

    private static void sendThroughTheGatewayFromTheFirstCaller(EnqueueOutsideUnitOfWorkGuard guard) {
        ApplicationGateway.send(guard);
    }

    private static void sendThroughTheGatewayFromTheSecondCaller(EnqueueOutsideUnitOfWorkGuard guard) {
        ApplicationGateway.send(guard);
    }

    /**
     * How many distinct innermost frames {@link #enqueueFromCallSite(EnqueueOutsideUnitOfWorkGuard, int)} has
     */
    private static final int DISTINCT_INNERMOST_FRAMES = 64;

    /**
     * A distinct innermost application frame per {@code site} (0-63) without a method per site: one call instruction
     * per site, so the bytecode index - and with it the innermost frame the cap is charged by - differs per site
     */
    private static void enqueueFromCallSite(EnqueueOutsideUnitOfWorkGuard guard, int site) {
        switch (site) {
            case 0 -> guard.checkEnqueue(QUEUE, false);
            case 1 -> guard.checkEnqueue(QUEUE, false);
            case 2 -> guard.checkEnqueue(QUEUE, false);
            case 3 -> guard.checkEnqueue(QUEUE, false);
            case 4 -> guard.checkEnqueue(QUEUE, false);
            case 5 -> guard.checkEnqueue(QUEUE, false);
            case 6 -> guard.checkEnqueue(QUEUE, false);
            case 7 -> guard.checkEnqueue(QUEUE, false);
            case 8 -> guard.checkEnqueue(QUEUE, false);
            case 9 -> guard.checkEnqueue(QUEUE, false);
            case 10 -> guard.checkEnqueue(QUEUE, false);
            case 11 -> guard.checkEnqueue(QUEUE, false);
            case 12 -> guard.checkEnqueue(QUEUE, false);
            case 13 -> guard.checkEnqueue(QUEUE, false);
            case 14 -> guard.checkEnqueue(QUEUE, false);
            case 15 -> guard.checkEnqueue(QUEUE, false);
            case 16 -> guard.checkEnqueue(QUEUE, false);
            case 17 -> guard.checkEnqueue(QUEUE, false);
            case 18 -> guard.checkEnqueue(QUEUE, false);
            case 19 -> guard.checkEnqueue(QUEUE, false);
            case 20 -> guard.checkEnqueue(QUEUE, false);
            case 21 -> guard.checkEnqueue(QUEUE, false);
            case 22 -> guard.checkEnqueue(QUEUE, false);
            case 23 -> guard.checkEnqueue(QUEUE, false);
            case 24 -> guard.checkEnqueue(QUEUE, false);
            case 25 -> guard.checkEnqueue(QUEUE, false);
            case 26 -> guard.checkEnqueue(QUEUE, false);
            case 27 -> guard.checkEnqueue(QUEUE, false);
            case 28 -> guard.checkEnqueue(QUEUE, false);
            case 29 -> guard.checkEnqueue(QUEUE, false);
            case 30 -> guard.checkEnqueue(QUEUE, false);
            case 31 -> guard.checkEnqueue(QUEUE, false);
            case 32 -> guard.checkEnqueue(QUEUE, false);
            case 33 -> guard.checkEnqueue(QUEUE, false);
            case 34 -> guard.checkEnqueue(QUEUE, false);
            case 35 -> guard.checkEnqueue(QUEUE, false);
            case 36 -> guard.checkEnqueue(QUEUE, false);
            case 37 -> guard.checkEnqueue(QUEUE, false);
            case 38 -> guard.checkEnqueue(QUEUE, false);
            case 39 -> guard.checkEnqueue(QUEUE, false);
            case 40 -> guard.checkEnqueue(QUEUE, false);
            case 41 -> guard.checkEnqueue(QUEUE, false);
            case 42 -> guard.checkEnqueue(QUEUE, false);
            case 43 -> guard.checkEnqueue(QUEUE, false);
            case 44 -> guard.checkEnqueue(QUEUE, false);
            case 45 -> guard.checkEnqueue(QUEUE, false);
            case 46 -> guard.checkEnqueue(QUEUE, false);
            case 47 -> guard.checkEnqueue(QUEUE, false);
            case 48 -> guard.checkEnqueue(QUEUE, false);
            case 49 -> guard.checkEnqueue(QUEUE, false);
            case 50 -> guard.checkEnqueue(QUEUE, false);
            case 51 -> guard.checkEnqueue(QUEUE, false);
            case 52 -> guard.checkEnqueue(QUEUE, false);
            case 53 -> guard.checkEnqueue(QUEUE, false);
            case 54 -> guard.checkEnqueue(QUEUE, false);
            case 55 -> guard.checkEnqueue(QUEUE, false);
            case 56 -> guard.checkEnqueue(QUEUE, false);
            case 57 -> guard.checkEnqueue(QUEUE, false);
            case 58 -> guard.checkEnqueue(QUEUE, false);
            case 59 -> guard.checkEnqueue(QUEUE, false);
            case 60 -> guard.checkEnqueue(QUEUE, false);
            case 61 -> guard.checkEnqueue(QUEUE, false);
            case 62 -> guard.checkEnqueue(QUEUE, false);
            default -> guard.checkEnqueue(QUEUE, false);
        }
    }

    /**
     * One wrapper ({@link ApplicationGateway#send}, the innermost application frame) reached from a distinct caller
     * per {@code caller} (0-63): each of the {@code depth} frames calls on from one of eight call instructions, picked
     * by one base-8 digit of {@code caller}, so the two frames past the wrapper in the call-site key differ per caller
     */
    private static void sendThroughTheGatewayFromCaller(EnqueueOutsideUnitOfWorkGuard guard, int caller, int depth) {
        var rest = caller / 8;
        if (depth == 1) {
            switch (caller % 8) {
                case 0 -> ApplicationGateway.send(guard);
                case 1 -> ApplicationGateway.send(guard);
                case 2 -> ApplicationGateway.send(guard);
                case 3 -> ApplicationGateway.send(guard);
                case 4 -> ApplicationGateway.send(guard);
                case 5 -> ApplicationGateway.send(guard);
                case 6 -> ApplicationGateway.send(guard);
                default -> ApplicationGateway.send(guard);
            }
            return;
        }
        switch (caller % 8) {
            case 0 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 1 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 2 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 3 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 4 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 5 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            case 6 -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
            default -> sendThroughTheGatewayFromCaller(guard, rest, depth - 1);
        }
    }

    /**
     * Stands in for {@code Outbox}/{@code DurableLocalCommandBus}: a framework class between the application and the
     * enqueue
     */
    private static final class FakeOutbox {
        static void send(EnqueueOutsideUnitOfWorkGuard guard) {
            guard.checkEnqueue(QUEUE, factoryWith(null));
        }
    }

    /**
     * An application's own wrapper around the framework - a messaging gateway every caller goes through
     */
    private static final class ApplicationGateway {
        static void send(EnqueueOutsideUnitOfWorkGuard guard) {
            FakeOutbox.send(guard);
        }
    }

    private List<String> warnings() {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                            .map(ILoggingEvent::getFormattedMessage)
                            .toList();
    }

    @Test
    void by_default_an_enqueue_outside_a_unit_of_work_goes_ahead() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);

        assertThat(guard.isRequireActiveUnitOfWork()).isFalse();
        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(null))).doesNotThrowAnyException();
        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(null))).as("and again - the WARN is once, never a throw").doesNotThrowAnyException();
    }

    @Test
    void when_required_an_enqueue_outside_a_unit_of_work_fails_and_names_the_property() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatThrownBy(() -> guard.checkEnqueue(QUEUE, factoryWith(null)))
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
    }

    @Test
    void when_required_a_missing_factory_counts_as_outside_a_unit_of_work() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatThrownBy(() -> guard.checkEnqueue(QUEUE, (UnitOfWorkFactory<? extends UnitOfWork>) null))
                .isInstanceOf(NoActiveUnitOfWorkException.class);
    }

    @Test
    void when_required_an_enqueue_inside_a_unit_of_work_goes_ahead() {
        var guard = new EnqueueOutsideUnitOfWorkGuard(DurableQueues.class);
        guard.setRequireActiveUnitOfWork(true);

        assertThatCode(() -> guard.checkEnqueue(QUEUE, factoryWith(mock(UnitOfWork.class)))).doesNotThrowAnyException();
    }

    @Test
    void a_spring_transaction_with_no_unit_of_work_bound_yet_counts_as_active_for_a_spring_aware_factory() {
        var springFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(() -> {
            throw new SQLException("No database in this test");
        }), mock(PlatformTransactionManager.class));

        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(springFactory)).isFalse();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(springFactory.getCurrentUnitOfWork())
                .as("what a plain @Transactional/TransactionTemplate looks like before anything joined it")
                .isEmpty();
        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(springFactory)).isTrue();
    }

    @Test
    void a_spring_transaction_does_not_count_for_a_factory_that_cannot_join_it() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThat(EnqueueOutsideUnitOfWorkGuard.isUnitOfWorkActive(factoryWith(null))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static UnitOfWorkFactory<UnitOfWork> factoryWith(UnitOfWork current) {
        UnitOfWorkFactory<UnitOfWork> factory = mock(UnitOfWorkFactory.class);
        when(factory.getCurrentUnitOfWork()).thenReturn(Optional.ofNullable(current));
        return factory;
    }
}
