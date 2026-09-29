package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}}

import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * INBOUND half of the {{externalSystem}} ACL (the Inbox pattern).
 *
 * External message in, internal command out. `sendAndDontWait` puts the command on the durable
 * command bus, so delivery survives a restart and is retried automatically — the handler must
 * therefore be idempotent on the receiving side.
 *
 * This is not a slice API in the R2 sense: it is the external system's ingress, not a public
 * endpoint of the bounded context. It still holds exactly one mapping.
 *
 * Delete this file if the slice is `direction: outbound`.
 */
@RestController
class On{{ExternalEvent}}(
    private val commandBus: CommandBus,
    private val translator: {{ExternalSystem}}Translator
) {

    @PostMapping("/webhooks/{{externalSystem}}")
    fun on(@RequestBody payload: {{ExternalEvent}}Payload) {
        commandBus.sendAndDontWait(translator.toCommand(payload))
    }
}
