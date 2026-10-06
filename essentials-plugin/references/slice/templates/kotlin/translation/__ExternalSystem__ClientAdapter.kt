package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}}

import org.springframework.stereotype.Component

/**
 * The transport behind [{{ExternalSystem}}Client] — the one class in this slice that talks to
 * {{ExternalSystem}} over the wire (REST client, SDK, message producer).
 *
 * TODO: implement [send] with the real transport. Until then the context starts, and every outbound
 * event fails in `{{ExternalSystem}}Publisher`: it is redelivered under the publisher's redelivery
 * policy and then dead-lettered, so nothing is lost silently. Do not replace the throw with a no-op —
 * a transport that drops messages is the one failure nobody notices.
 *
 * Delete this file if the slice is `direction: inbound`.
 */
@Component
class {{ExternalSystem}}ClientAdapter : {{ExternalSystem}}Client {

    override fun send(request: {{ExternalSystem}}Request) {
        throw UnsupportedOperationException("TODO: send $request to {{ExternalSystem}}")
    }
}
