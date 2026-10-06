package {{packagePath}}.{{bc}}.routing;

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;

/**
 * Aggregate routing interface for the {{Aggregate}} aggregate (BC-private).
 *
 * Every concrete command — each in its own command slice under {@code use_cases/} — implements this
 * marker so the {@code EventStreamDeciderAndAggregateTypeConfigurator} can route it to the
 * {{Aggregate}} aggregate and extract the aggregate id.
 *
 * Routing interfaces live in {@code routing/}, never in {@code use_cases/}. Deliberately NOT sealed:
 * adding a command is an open/closed extension, so no existing file changes — which is exactly why
 * commands need no {@code permits} clause while events do.
 */
public interface {{Aggregate}}Command {
    {{Aggregate}}Id id();
}
