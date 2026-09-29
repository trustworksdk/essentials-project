package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}};

/**
 * Typed outbound port to the {{ExternalSystem}} system.
 *
 * An interface, so the publisher and the translator can be tested without the external system, and
 * so the transport (REST client, SDK, message producer) is swappable without touching the ACL.
 *
 * It speaks only external types — that is the point of the boundary.
 */
public interface {{ExternalSystem}}Client {
    void send({{ExternalSystem}}Translator.{{ExternalSystem}}Request request);
}
