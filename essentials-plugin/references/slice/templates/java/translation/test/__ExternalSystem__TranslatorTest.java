package {{packagePath}}.{{bc}}.external_systems.{{externalSystem}};

import org.junit.jupiter.api.Test;

/**
 * The test floor for a translation slice.
 *
 * The translator is pure, so this runs with no database, no Spring context, and no external system.
 * That is the whole reason the mapping lives in its own class.
 *
 * CONTRACT TESTING: a translation slice's real floor is a consumer contract test against
 * {{ExternalSystem}}'s published contract. This plugin bundles no contract-testing tooling — add
 * Pact or Specmatic yourself and flip {@code tests.contract.present} in slice.yaml once you have.
 */
class {{ExternalSystem}}TranslatorTest {

    private final {{ExternalSystem}}Translator translator = new {{ExternalSystem}}Translator();

    @Test
    void inboundPayloadMapsOntoTheInternalCommand() {
        // TODO: assert every field, including the type conversions at the boundary.
    }

    @Test
    void outboundEventMapsOntoTheExternalRequest() {
        // TODO: assert the external contract shape, not our internal one.
    }

    @Test
    void malformedExternalInputIsRejectedAtTheBoundary() {
        // A malformed external message must fail HERE, not three layers in.
    }
}
