package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file, never an extra method here.
 *
 * NEVER RETURN THE AGGREGATE. {@link {{packagePath}}.{{bc}}.aggregates.{{Aggregate}}} is the write
 * model: handing one to a caller makes its internals part of your wire contract, and exposes an
 * object whose state is only meaningful inside a unit of work. Return an id, a 202, or nothing. If
 * the client needs state back, that is a *query* and it belongs to a view slice.
 *
 * The command IS the contract (§R2) — no adapter layer, no mapper. The typed {@code {{Aggregate}}Id}
 * path variable binds because the Essentials converter is registered
 * ({@code references/stack/stack-contract.md} S4); a missing converter surfaces as HTTP 500, not 400.
 *
 * This is the "act on an existing aggregate" shape. For a *creation* slice, generate the id here
 * ({@code {{Aggregate}}Id.random()}), take no path variable, and return the new id — mirroring the
 * creation variant in {@link {{Slice}}Handler}.
 */
@RestController
@RequestMapping("{{apiPath}}")
public class {{Slice}}API {

    private final CommandBus commandBus;

    public {{Slice}}API(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    public record {{Slice}}Request(String placeholder) {}

    @PostMapping("/{id}")
    public void {{sliceCamel}}(@PathVariable("id") {{Aggregate}}Id {{aggregate}}Id,
                               @RequestBody {{Slice}}Request body) {
        commandBus.send(new {{Command}}({{aggregate}}Id, body.placeholder()));
    }
}
