package com.example.shop

import com.example.shop.orders.events.OrderPlaced
import com.example.shop.orders.types.OrderId
import com.example.shop.orders.use_cases.place_order.PlaceOrder
import com.example.shop.payments.events.PaymentRequested
import com.example.shop.payments.types.PaymentId
import com.example.shop.payments.use_cases.request_payment.RequestPayment
import dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator
import dk.trustworks.essentials.reactive.command.CommandBus
import dk.trustworks.essentials.reactive.command.MultipleCommandHandlersFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

/**
 * CI-only boot check for the two-BC slice compositions: two decider bounded contexts scaffolded by
 * /essentials:add-slice start in one context (no duplicate bean) and each command reaches its own BC's decider
 * through one configurator. A configurator per BC compiles and registers every BC's adapters twice, which only
 * the first command sent through the bus reveals.
 */
class TwoBoundedContextsBootIT : IntegrationTestBase() {

    @Autowired
    private lateinit var commandBus: CommandBus

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the application has one decider configurator`() {
        assertThat(context.getBeansOfType(DeciderAndAggregateTypeConfigurator::class.java)).hasSize(1)
    }

    @Test
    fun `each bounded context handles its own command`() {
        assertThat(send(PlaceOrder(OrderId.random(), "boot-check"))).isInstanceOf(OrderPlaced::class.java)
        assertThat(send(RequestPayment(PaymentId.random(), "boot-check"))).isInstanceOf(PaymentRequested::class.java)
    }

    private fun <C : Any> send(command: C): Any? =
        try {
            commandBus.send<Any?, C>(command)
        } catch (e: MultipleCommandHandlersFoundException) {
            fail<Any?>("more than one command handler for ${command::class.simpleName}: " +
                 "the application registers a decider configurator per bounded context", e)
        }
}
