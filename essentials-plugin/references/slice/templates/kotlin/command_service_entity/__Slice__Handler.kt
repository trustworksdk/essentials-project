package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.entities.{{Entity}}Repository
import {{packagePath}}.{{bc}}.events.{{Event}}
import dk.trustworks.essentials.reactive.EventBus
import dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler
import dk.trustworks.essentials.reactive.command.CmdHandler
import org.springframework.stereotype.Component

/**
 * The decision component for THIS slice (rules/slice-design.md §R1).
 *
 * ONE `@CmdHandler` METHOD, ONE COMMAND TYPE. A handler class carrying methods for two or more
 * command types is R1's router wearing a handler's clothes — it splits into that many slices.
 *
 * The shape is always the same four steps: **load, call the one invariant method, save, publish.**
 * The decision belongs on the entity, not here. An `if` about domain state in this file belongs on
 * `{{Entity}}` (§ The entity's own bar).
 *
 * WIRING — normally nothing to write. `ReactiveHandlersBeanPostProcessor` auto-registers any
 * `CommandHandler` bean with the single `CommandBus` bean, so `@Component` in a scanned package is
 * the whole of it. The obligation is a *check*: confirm `reactive-bean-post-processor-enabled`
 * (default `true`) is not switched off, because disabling it silently unwires every handler.
 *
 * TRANSACTION — no `@Transactional` here. The `CommandBus` already hands the command to this handler
 * inside a unit of work (the starter's `UnitOfWorkControllingCommandBusInterceptor`), and that unit of
 * work is a Spring-managed transaction the repository joins, so the load, the mutation, the save and
 * the publish run in one. `@Transactional` would make Spring proxy this class, and
 * `AnnotatedCommandHandler.handle` is final: the bus would call it on the proxy, whose fields are
 * never initialised, and every command would fail with a `NullPointerException`.
 */
@Component
class {{Slice}}Handler(
    private val {{entity}}s: {{Entity}}Repository,
    private val eventBus: EventBus
) : AnnotatedCommandHandler() {

    @CmdHandler
    fun handle(cmd: {{Command}}) {
        val {{entity}} = {{entity}}s.findById(cmd.id.value)
            ?: throw IllegalArgumentException("No {{Entity}} ${cmd.id.value}")

        // TODO: call the ONE invariant method this slice's intent maps to. The Boolean-returning
        //       shape is the idempotent form — false means the state was already reached, so a
        //       redelivered command is a no-op rather than a duplicate event.
        if (!{{entity}}.applyPlaceholder(cmd.placeholder)) return

        {{entity}}s.save({{entity}})
        eventBus.publish({{Event}}(cmd.id, cmd.placeholder))
    }
}
