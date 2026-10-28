package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file.
 *
 * NEVER RETURN THE ENTITY. On this lane the entity is a managed, mutable persistence object;
 * returning one makes every field of the write model part of your wire contract. Return an id, a
 * 202, or nothing. If the client needs state back, that is a *query* and belongs to a view slice
 * (§ The read side on this lane).
 *
 * The command IS the contract (§R2) — no adapter layer, no mapper. `{{Slice}}Request` carries only
 * the fields the client actually sends: the id of the {{Entity}} to change, and the change. This
 * slice changes an existing {{Entity}} — the handler loads it — so the id comes from the client,
 * never from `{{Aggregate}}Id.random()`, which would name a row that does not exist. Turning the
 * request into the command is *assembly*.
 */
@RestController
@RequestMapping("{{apiPath}}")
class {{Slice}}API(private val commandBus: CommandBus) {

    data class {{Slice}}Request(val {{aggregate}}Id: String, val placeholder: String)
    data class {{Slice}}Response(val {{aggregate}}Id: String)

    @PostMapping
    fun {{sliceCamel}}(@RequestBody body: {{Slice}}Request): {{Slice}}Response {
        val id = {{Aggregate}}Id.of(body.{{aggregate}}Id)
        commandBus.send<Any?, {{Command}}>({{Command}}(id, body.placeholder))
        return {{Slice}}Response(id.value)
    }
}
