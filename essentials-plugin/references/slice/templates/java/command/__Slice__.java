package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.routing.{{Aggregate}}Command;
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;

/**
 * Command for the {{slice}} slice — the intent, as data.
 *
 * A {@code record}: commands are immutable value objects. Implements {@link {{Aggregate}}Command} so
 * the {@code EventStreamDeciderAndAggregateTypeConfigurator} can route it to the {{Aggregate}}
 * aggregate and extract the aggregate id.
 *
 * The command interface is deliberately NOT sealed: adding a command is an open/closed extension
 * (a new slice), never an edit to an existing hierarchy.
 */
public record {{Command}}(
        {{Aggregate}}Id id,
        // TODO: replace with this command's real payload
        String placeholder
) implements {{Aggregate}}Command {
}
