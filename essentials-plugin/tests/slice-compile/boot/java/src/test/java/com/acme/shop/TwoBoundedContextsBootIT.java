package com.acme.shop;

import com.acme.shop.orders.events.OrderPlaced;
import com.acme.shop.orders.types.OrderId;
import com.acme.shop.orders.use_cases.place_order.PlaceOrder;
import com.acme.shop.payments.events.PaymentRequested;
import com.acme.shop.payments.types.PaymentId;
import com.acme.shop.payments.use_cases.request_payment.RequestPayment;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.reactive.command.MultipleCommandHandlersFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CI-only boot check for the two-BC slice compositions: two decider bounded contexts scaffolded by
 * /essentials:add-slice start in one context (no duplicate bean) and each command reaches its own BC's decider
 * through one configurator. A configurator per BC compiles and registers every BC's adapters twice, which only
 * the first command sent through the bus reveals.
 */
class TwoBoundedContextsBootIT extends IntegrationTestBase {

    @Autowired
    private CommandBus commandBus;

    @Autowired
    private ApplicationContext context;

    @Test
    void the_application_has_one_decider_configurator() {
        assertThat(context.getBeansOfType(EventStreamDeciderAndAggregateTypeConfigurator.class)).hasSize(1);
    }

    @Test
    void each_bounded_context_handles_its_own_command() {
        assertThat(send(new PlaceOrder(OrderId.random(), "boot-check"))).isInstanceOf(OrderPlaced.class);
        assertThat(send(new RequestPayment(PaymentId.random(), "boot-check"))).isInstanceOf(PaymentRequested.class);
    }

    private Object send(Object command) {
        try {
            return commandBus.send(command);
        } catch (MultipleCommandHandlersFoundException e) {
            return fail("more than one command handler for " + command.getClass().getSimpleName()
                                + ": the application registers a decider configurator per bounded context", e);
        }
    }
}
