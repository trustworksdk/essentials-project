package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}};

/**
 * Anti-corruption layer for the {{ExternalSystem}} system — the ONLY place the external schema is
 * allowed to appear.
 *
 * This class is deliberately <strong>pure</strong>: no Spring, no Essentials, no I/O imports. That
 * is what makes the mapping unit-testable without either side running, and it is the test floor for
 * a translation slice.
 *
 * Everything past this boundary speaks {{ExternalSystem}}'s language; nothing inside {@code {{bc}}}
 * does. If an external type leaks into a Decider, an event, or a view, the ACL has failed.
 */
public class {{ExternalSystem}}Translator {

    /** Inbound: external message -> internal command. Record it in slice.yaml {@code maps}. */
    public Object toCommand({{ExternalEvent}}Payload external) {
        // TODO: map external fields onto the internal command, converting types at this boundary
        //       (external ids/strings/dates -> the BC's semantic types).
        throw new UnsupportedOperationException("map {{ExternalEvent}}Payload -> internal command");
    }

    /** Outbound: internal event -> external request shape. */
    public {{ExternalSystem}}Request toExternal(Object event) {
        // TODO: map the internal event onto the external contract.
        throw new UnsupportedOperationException("map internal event -> {{ExternalSystem}}Request");
    }

    /** External wire shape — mirrors {{ExternalSystem}}'s contract, not ours. */
    public record {{ExternalEvent}}Payload(String id, String payload) {}

    /** External request shape — mirrors {{ExternalSystem}}'s contract, not ours. */
    public record {{ExternalSystem}}Request(String reference, String payload) {}
}
