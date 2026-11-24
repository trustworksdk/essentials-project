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
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ESS-145: with the guard registered (as {@link EventLogAutoConfiguration} registers it while the event log is enabled),
 * a classic processor bean fails start-up before it is created, with a message naming the bean, its class and what to
 * port it to; a context without one is unaffected.
 */
class UnportedEventProcessorGuardTest {
    /** Never instantiated: a bean of it would fail creation for want of its dependencies, which the guard must pre-empt */
    abstract static class OrderProjection extends ViewEventProcessor {
        OrderProjection(ViewEventProcessorDependencies dependencies) {
            super(dependencies);
        }
    }

    abstract static class OrderNotifier extends EventProcessor {
        OrderNotifier(EventProcessorDependencies dependencies) {
            super(dependencies);
        }
    }

    abstract static class InventoryView extends InTransactionEventProcessor {
        InventoryView(EventProcessorDependencies dependencies) {
            super(dependencies, true);
        }
    }

    abstract static class AccountOverview extends PartitionedViewEventProcessor {
        AccountOverview(ViewEventProcessorDependencies dependencies) {
            super(dependencies);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withBean(UnportedEventProcessorGuard.class);

    @Test
    void an_old_view_processor_bean_fails_start_up_with_the_exact_message() {
        runner.withBean("orderProjection", OrderProjection.class, () -> {
                  throw new AssertionError("must not be created");
              })
              .run(context -> assertThat(context.getStartupFailure())
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessage("Bean 'orderProjection' (" + OrderProjection.class.getName() + ") is an old-engine ViewEventProcessor. " +
                                          "With essentials.event-log.enabled=true it must be ported to EventLogViewProcessor - " +
                                          "see LLM-postgresql-event-log.md § Porting from the EventProcessor family."));
    }

    @Test
    void every_old_processor_bean_is_named_with_its_suggested_port() {
        runner.withBean("orderProjection", OrderProjection.class, () -> null)
              .withBean("orderNotifier", OrderNotifier.class, () -> null)
              .withBean("inventoryView", InventoryView.class, () -> null)
              .withBean("accountOverview", AccountOverview.class, () -> null)
              .run(context -> assertThat(context.getStartupFailure().getMessage())
                      .contains("Bean 'orderProjection' (" + OrderProjection.class.getName() + ") is an old-engine ViewEventProcessor", "ported to EventLogViewProcessor")
                      .contains("Bean 'orderNotifier' (" + OrderNotifier.class.getName() + ") is an old-engine EventProcessor",
                                "ported to EventLogInboxProcessor (with an inbox) or EventLogReactor (side effects only)")
                      .contains("Bean 'inventoryView' (" + InventoryView.class.getName() + ") is an old-engine InTransactionEventProcessor")
                      .contains("Bean 'accountOverview' (" + AccountOverview.class.getName() + ") is an old-engine PartitionedViewEventProcessor",
                                "ported to EventLogPartitionedViewProcessor"));
    }

    @Test
    void a_context_without_old_processors_starts() {
        runner.withBean("somethingElse", String.class, () -> "fine")
              .run(context -> {
                  assertThat(context).hasNotFailed();
                  assertThat(context).hasBean("somethingElse");
              });
    }

    @Test
    void the_auto_configuration_registers_the_guard_as_a_static_bean_method() throws Exception {
        var method = EventLogAutoConfiguration.class.getDeclaredMethod("unportedEventProcessorGuard");
        assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
        assertThat(Modifier.isStatic(method.getModifiers())).as("a BeanFactoryPostProcessor bean method must be static").isTrue();
        assertThat(method.getReturnType()).isEqualTo(UnportedEventProcessorGuard.class);
    }
}
