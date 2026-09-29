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
 * the fields the client actually sends, because the id is generated here; that is *assembly*.
 */
@RestController
@RequestMapping("{{apiPath}}")
class {{Slice}}API(private val commandBus: CommandBus) {

    data class {{Slice}}Request(val placeholder: String)
    data class {{Slice}}Response(val {{aggregate}}Id: String)

    @PostMapping
    fun {{sliceCamel}}(@RequestBody body: {{Slice}}Request): {{Slice}}Response {
        val id = {{Aggregate}}Id.random()
        commandBus.send({{Command}}(id, body.placeholder))
        return {{Slice}}Response(id.value)
    }
}
