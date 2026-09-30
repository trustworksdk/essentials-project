package com.acme.shop.orders.use_cases.cancel_order

import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file, never an extra method here.
 *
 * The command IS the contract (§R2) — no adapter layer. [CancelOrder] is the @RequestBody itself:
 * no DTO mirroring it, no mapper, nothing to keep in sync. This also makes [CancelOrderDecider]'s
 * idempotency guard live, because the client supplies the aggregate id and a retry therefore replays
 * the same one instead of generating a fresh id per attempt.
 *
 * REGISTRATION THIS DEPENDS ON — confirm it, do not infer it from a dependency being present:
 * `OrderId` is a `@JvmInline value class`, and Kotlin value types are NOT covered by
 * `EssentialTypesJacksonModule`. BOTH mappers need `jackson-module-kotlin`'s `KotlinModule`, or the
 * id serializes as `{"value":"…"}` instead of `"…"` — silently, with nothing thrown:
 * - the WEB mapper finds it on the classpath (Boot's `spring.jackson.find-and-add-modules`);
 * - the PERSISTENCE mapper does not — the starters' `JSONSerializer` deliberately ignores
 *   `JacksonModule` beans, so the project must define its own serializer bean built with
 *   `EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build())`
 *   (references/stack/kotlin-spring-boot.md § Serialization — S3.4 in full).
 * A service that persists or publishes the wrapped form has changed its contract with no error to
 * notice. See LLM-types-jackson.md § Kotlin semantic types.
 *
 * If you have checked and that module is genuinely absent, fall back to a small body type carrying
 * the fields the client sends plus a server-generated id — and record it in the slice's CLAUDE.md so
 * it reads as a constraint rather than as habit.
 *
 * Typed @PathVariable (not used here — this slice creates the aggregate) needs NOTHING from
 * Essentials for a `@JvmInline value class` id: Kotlin unboxes it in the JVM signature, so Spring
 * sees a String and binds it natively, on any version. Only a NON-inline wrapper over a non-String
 * needs `KotlinValueTypeConverter` from `types-spring-web` (registered by importing
 * `EssentialsWebFluxConfigurer` / `EssentialsWebMvcConfigurer` — that module auto-configures nothing).
 *
 * Sends the command on the Essentials `CommandBus`; the `DeciderAndAggregateTypeConfigurator`
 * (see orders/config) routes it to [CancelOrderDecider], loads the event stream, and persists the
 * resulting event.
 */
@RestController
@RequestMapping("/api/orders/cancel")
class CancelOrderAPI(private val commandBus: CommandBus) {

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancelOrder(@RequestBody command: CancelOrder) {
        commandBus.send<Any?, CancelOrder>(command)
    }
}
