package com.example.golden.wiring

import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.reactive.command.CommandBus
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/** A Kotlin id: Kotlin unboxes it in JVM signatures, so it binds with nothing from Essentials. */
@JvmInline
value class ProbeId(val value: String)

/** `related` holds the id boxed (a List element), which is where a missing KotlinModule shows as {"value":"…"}. */
data class ProbeDocument(val id: ProbeId, val related: List<ProbeId>)

/**
 * CI-only (never rendered into a user project). Gives the generated spec a real endpoint, and injects the beans a
 * slice controller typically needs, so OpenApiContractIT is proven to generate the spec with them.
 */
@RestController
class WiringProbeController(
    private val jsonSerializer: JSONSerializer,
    private val commandBus: CommandBus
) {
    /** S3.4: written as a JSON string on the web mapper only when KotlinModule reaches it. */
    @Operation(operationId = "echo") // a value-class parameter mangles the JVM method name springdoc would use
    @GetMapping("/api/wiring/{id}")
    fun echo(@PathVariable id: ProbeId): ProbeDocument = ProbeDocument(id, listOf(id))

    /** S3.4: the persistence mapper gets KotlinModule only through config/PersistenceSerializerConfiguration. */
    @Operation(operationId = "persisted")
    @GetMapping("/api/wiring/{id}/persisted", produces = ["text/plain"])
    fun persisted(@PathVariable id: ProbeId): String = jsonSerializer.serialize(ProbeDocument(id, listOf(id)))

    @GetMapping("/api/wiring/command-bus", produces = ["text/plain"])
    fun commandBus(): String = commandBus.javaClass.simpleName
}
