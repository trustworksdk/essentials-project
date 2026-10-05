package com.example.shop.orders.views.order_list

import com.example.shop.orders.types.OrderId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. **Several query methods are legitimate** when they interrogate
 * the same read shape — filters, sorts, pagination, lookup-by-id. A query serving a *different*
 * purpose over a *different* shape is a different slice. Declare every method in slice.yaml `serves`
 * + `endpoints`.
 *
 * The read shape IS the response (§R2) — return [OrderListView]. No mirror type, no mapper.
 *
 * Never inject `OrderRepository` (the BC's write repository), never call `save`/`delete`, never
 * touch another slice's queries.
 *
 * The path variable is the BC's semantic id, not a `String` — the typed edge is the default shape
 * (rules/slice-design.md § The command and the view *are* the contract). A `@JvmInline value class`
 * binds with nothing from Essentials. Only the query stays `String`-keyed, because the entity's `@Id`
 * is a `String` (`entities/CLAUDE.md`), so the id is unwrapped with `.value` at the one call that
 * needs it.
 *
 * A value class directly in the signature makes Kotlin mangle the JVM method name
 * (`byOrderId-<hash>`), and springdoc publishes that as the operationId, so `@Operation` pins
 * it (`references/llm/LLM-types-spring-web.md` § Kotlin handler methods: set the operationId).
 */
@RestController
@RequestMapping("/api/orders")
class OrderListAPI(private val queries: OrderListQueries) {

    @GetMapping
    fun orderList(): List<OrderListView> = queries.findAllBy()

    @Operation(operationId = "orderListByOrderId")
    @GetMapping("/{orderId}")
    fun byOrderId(@PathVariable orderId: OrderId): OrderListView =
        queries.findOrderById(orderId.value)
            ?: throw NoSuchElementException(orderId.value)
}
