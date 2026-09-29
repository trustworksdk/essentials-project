package {{packagePath}}.{{bc}}.routing

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id

/**
 * Aggregate routing interface for the {{Aggregate}} aggregate (BC-private).
 *
 * Every concrete command — each in its own command slice under `use_cases/` — implements this
 * marker so the `DeciderAndAggregateTypeConfigurator` can route it to the {{Aggregate}} aggregate
 * and extract the aggregate id.
 *
 * Routing interfaces live in `routing/`, never in `use_cases/`. This interface is deliberately NOT
 * sealed: adding a command is an open/closed extension, so no existing file changes.
 */
interface {{Aggregate}}Command {
    val id: {{Aggregate}}Id
}
