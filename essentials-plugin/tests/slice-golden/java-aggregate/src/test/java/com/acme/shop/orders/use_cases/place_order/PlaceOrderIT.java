package com.acme.shop.orders.use_cases.place_order;

import com.acme.shop.orders.aggregates.Orders;
import com.acme.shop.orders.types.OrderId;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for THIS slice.
 *
 * IT SENDS THROUGH THE {@link CommandBus} RATHER THAN CALLING THE HANDLER. That is the entire point:
 * handler registration is automatic ({@code ReactiveHandlersBeanPostProcessor}), which means it can
 * be switched off by configuration without breaking a compile or a unit test. Only a test that goes
 * through the bus notices. Calling {@code handler.handle(cmd)} here would pass in an application
 * whose handlers are all silently unwired.
 *
 * Extend the project's integration-test base class (see {@code references/stack/stack-contract.md}
 * S10) so this shares the cached Spring context and the reused Testcontainer.
 */
class PlaceOrderIT /* extends IntegrationTestBase */ {

    @Autowired
    CommandBus commandBus;

    @Autowired
    Orders orders;

    @Test
    void handles_the_command_and_appends_the_event() {
        // TODO: extend the project's IntegrationTestBase (until then nothing is injected), then:
        //   var id = OrderId.random();
        //   create the aggregate first — through its creation slice, not by reaching into the
        //   repository, so the test exercises the same path production does;
        //   commandBus.send(new PlaceOrder(id, "updated"));
        //   assertThat(orders.getOrder(id).placeholder()).isEqualTo("updated");
    }
}
