package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.aggregates.{{Aggregates}};
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for THIS slice.
 *
 * IT SENDS THROUGH THE {@link CommandBus} RATHER THAN CALLING THE HANDLER. That is the entire point:
 * handler registration is automatic ({@code ReactiveHandlersBeanPostProcessor}), which means it can
 * be switched off by configuration without breaking a compile or a unit test. Only a test that goes
 * through the bus notices. Calling {@code handler.handle(cmd)} here would pass in an application
 * whose handlers are all silently unwired.
 *
 * Extend the project's integration-test base class (see {@code references/stack/stack-contract.md}
 * S10) so this shares the cached Spring context and the reused Testcontainer.
 */
class {{Slice}}IT /* extends IntegrationTestBase */ {

    @Autowired
    CommandBus commandBus;

    @Autowired
    {{Aggregates}} {{aggregate}}s;

    @Test
    void handles_the_command_and_appends_the_event() {
        var id = {{Aggregate}}Id.random();
        // TODO: create the aggregate first — through its creation slice, not by reaching into the
        //       repository, so the test exercises the same path production does.

        commandBus.send(new {{Command}}(id, "updated"));

        assertThat({{aggregate}}s.get{{Aggregate}}(id).placeholder()).isEqualTo("updated");
    }
}
