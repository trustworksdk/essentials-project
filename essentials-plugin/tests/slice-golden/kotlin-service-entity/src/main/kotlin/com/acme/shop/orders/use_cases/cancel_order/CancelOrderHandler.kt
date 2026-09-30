package com.acme.shop.orders.use_cases.cancel_order

import com.acme.shop.orders.entities.OrderRepository
import com.acme.shop.orders.events.OrderCancelled
import dk.trustworks.essentials.reactive.EventBus
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler
import dk.trustworks.essentials.reactive.command.CmdHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * The decision component for THIS slice (rules/slice-design.md §R1).
 *
 * ONE `@CmdHandler` METHOD, ONE COMMAND TYPE. A handler class carrying methods for two or more
 * command types is R1's router wearing a handler's clothes — it splits into that many slices.
 *
 * The shape is always the same four steps: **load, call the one invariant method, save, publish.**
 * The decision belongs on the entity, not here. An `if` about domain state in this file belongs on
 * `Order` (§ The entity's own bar).
 *
 * WIRING — normally nothing to write. `ReactiveHandlersBeanPostProcessor` auto-registers any
 * `CommandHandler` bean with the single `CommandBus` bean, so `@Component` in a scanned package is
 * the whole of it. The obligation is a *check*: confirm `reactive-bean-post-processor-enabled`
 * (default `true`) is not switched off, because disabling it silently unwires every handler.
 */
@Component
class CancelOrderHandler(
    private val orders: OrderRepository,
    private val eventBus: EventBus
) : AnnotatedCommandHandler() {

    @CmdHandler
    @Transactional
    fun handle(cmd: CancelOrder) {
        val order = orders.findById(cmd.id.value)
            ?: throw IllegalArgumentException("No Order ${cmd.id.value}")

        // TODO: call the ONE invariant method this slice's intent maps to. The Boolean-returning
        //       shape is the idempotent form — false means the state was already reached, so a
        //       redelivered command is a no-op rather than a duplicate event.
        if (!order.applyPlaceholder(cmd.placeholder)) return

        orders.save(order)
        eventBus.publish(OrderCancelled(cmd.id, cmd.placeholder))
    }
}
