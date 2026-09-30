package {{packagePath}}.orders.use_cases.cancel_order

import {{packagePath}}.orders.types.OrderId
import dk.trustworks.essentials.reactive.command.CommandBus
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Single-method endpoint for THIS slice only. Its own controller — not added to PlaceOrderAPI.
 *
 * `orderId` is typed as [OrderId], not String. This needs NOTHING from Essentials: OrderId is a
 * `@JvmInline value class`, and Kotlin unboxes a value class in every JVM signature, so this method
 * compiles to `cancelOrder-<hash>(String, …)` and Spring binds a plain String natively, re-boxing it
 * before invoking the handler. No converter, no `types-spring-web` dependency, no version floor.
 *
 * A NON-inline wrapper would be different — over a String, Spring's own ObjectToObjectConverter finds
 * the single-arg constructor; over anything else it needs `KotlinValueTypeConverter` from
 * `types-spring-web`, which is registered by the @Import in config/EssentialsWebConfig.kt.
 *
 * The same mangling reaches the OpenAPI spec: springdoc names the operation after the JVM method
 * (`cancelOrder-<hash>`), so `@Operation(operationId = "cancelOrder")` pins the name clients generate from.
 *
 * The body carries only `reason` — the id arrives by path, so this is assembly, not an adapter
 * (rules/slice-design.md §R2).
 */
@RestController
@RequestMapping("/api/orders")
class CancelOrderAPI(private val commandBus: CommandBus) {

    data class CancelOrderRequest(val reason: String)

    @Operation(operationId = "cancelOrder")
    @PostMapping("/{orderId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancelOrder(@PathVariable orderId: OrderId, @RequestBody body: CancelOrderRequest) {
        commandBus.send<Any?, CancelOrder>(CancelOrder(orderId, body.reason))
    }
}
