package {{packagePath}}.orders.use_cases.place_order

import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2). NEVER a
 * multi-endpoint controller injecting many handlers — adding a command adds a new
 * slice + its own API file, never an endpoint to a shared controller.
 *
 * The command IS the contract: [PlaceOrder] is the @RequestBody itself. No DTO
 * mirrors it and no mapper stands between the wire and the command.
 *
 * The client supplies the OrderId, which is what makes [PlaceOrderDecider]'s
 * idempotency guard real: a retry replays the SAME id, the decider sees the
 * existing OrderPlaced event, and returns null. Had the id been generated here,
 * every retry would create a new order and the guard would never fire.
 *
 * This depends on Jackson 3's KotlinModule (`tools.jackson.module:jackson-module-kotlin`)
 * on BOTH mappers: the web mapper that binds this request body, and the persistence
 * mapper that writes the OrderPlaced event — the starter's serializer ignores module
 * beans, so that one is your own serializer built on
 * `EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule…)`. OrderId is a
 * `@JvmInline value class`, and the Essentials Jackson module does not cover Kotlin
 * value types. Without KotlinModule the id serializes as {"value":"…"} instead of
 * "…" — silently, with nothing thrown. See stack-contract.md S3.2–S3.4 and
 * kotlin-spring-boot.md § Serialization.
 *
 * Sends the command on the Essentials `CommandBus`; the `DeciderAndAggregateType-
 * Configurator` (see orders/config) routes it to [PlaceOrderDecider], loads the
 * stream, and persists the resulting event.
 */
@RestController
@RequestMapping("/api/orders")
class PlaceOrderAPI(private val commandBus: CommandBus) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun placeOrder(@RequestBody command: PlaceOrder) {
        commandBus.send(command)
    }
}
