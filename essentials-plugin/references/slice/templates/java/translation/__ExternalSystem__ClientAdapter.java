package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}};

import org.springframework.stereotype.Component;

/**
 * The transport behind {@link {{ExternalSystem}}Client} — the one class in this slice that talks to
 * {{ExternalSystem}} over the wire (REST client, SDK, message producer).
 *
 * TODO: implement {@link #send} with the real transport. Until then the context starts, and every
 * outbound event fails in {@code {{ExternalSystem}}Publisher}: it is redelivered under the publisher's
 * redelivery policy and then dead-lettered, so nothing is lost silently. Do not replace the throw with
 * a no-op — a transport that drops messages is the one failure nobody notices.
 *
 * Delete this file if the slice is {@code direction: inbound}.
 */
@Component
public class {{ExternalSystem}}ClientAdapter implements {{ExternalSystem}}Client {

    @Override
    public void send({{ExternalSystem}}Translator.{{ExternalSystem}}Request request) {
        throw new UnsupportedOperationException("TODO: send " + request + " to {{ExternalSystem}}");
    }
}
