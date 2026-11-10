package com.example.shop.orders.external_systems.billing

import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

/**
 * INBOUND half of the billing ACL.
 *
 * External message in, internal command out. `sendAndDontWait` puts the command on the durable
 * command bus (a `DurableLocalCommandBus` queues it on `DurableQueues`), so delivery survives a
 * restart and is retried automatically — the handler must therefore be idempotent on the
 * receiving side.
 *
 * This is not a slice API in the R2 sense: it is the external system's ingress, not a public
 * endpoint of the bounded context. It still holds exactly one mapping, on a path of its own per
 * external event, so a second ingress for Billing never collides with this one.
 *
 * AUTHENTICATION FAILS CLOSED. The endpoint is reachable by anyone who can reach the application,
 * and whatever it accepts becomes a command. Until `webhooks.billing.shared-secret` is
 * configured, every request is rejected with 401; once it is, a request must present the same value
 * in the `X-Webhook-Secret` header (compared in constant time). That is a placeholder, not
 * Billing's scheme — see the TODO on `authenticate` (stack contract S9).
 *
 * The handler is named for the external event, not `on`: springdoc uses the method name as the
 * operationId, so each translation slice's ingress keeps a unique, stable name in the generated client.
 *
 * Delete this file if the slice is `direction: outbound`.
 */
@RestController
class OnInvoiceIssued(
    private val commandBus: CommandBus,
    @Value("\${webhooks.billing.shared-secret:}") private val sharedSecret: String
) {

    // The translator is pure (no Spring), so it is constructed here rather than injected.
    private val translator = BillingTranslator()

    @PostMapping("/webhooks/billing/InvoiceIssued")
    fun onInvoiceIssued(
        @RequestHeader(name = "X-Webhook-Secret", required = false) presentedSecret: String?,
        @RequestBody payload: InvoiceIssuedPayload
    ) {
        authenticate(presentedSecret)
        commandBus.sendAndDontWait(translator.toCommand(payload))
    }

    // TODO: replace this shared-secret comparison with Billing's own scheme — usually an
    //       HMAC signature over the raw request body plus a timestamp to refuse replays — and keep it
    //       failing closed: no configured secret must still mean no request gets through. Supply the
    //       secret from the environment (WEBHOOKS_..._SHARED_SECRET), never from application.yml.
    private fun authenticate(presentedSecret: String?) {
        if (sharedSecret.isBlank() ||
            presentedSecret == null ||
            !MessageDigest.isEqual(sharedSecret.toByteArray(Charsets.UTF_8), presentedSecret.toByteArray(Charsets.UTF_8))
        ) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }
    }
}
