package {{packagePath}}.{{bc}}.use_cases.{{slice}}

import {{packagePath}}.{{bc}}.routing.{{Aggregate}}Command
import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id

/**
 * Command for the {{slice}} slice — the intent, as data.
 *
 * Implements [{{Aggregate}}Command] so the `DeciderAndAggregateTypeConfigurator` can route it to the
 * {{Aggregate}} aggregate and extract the aggregate id. Commands are NOT sealed: adding a command is
 * an open/closed extension (a new slice), never an edit to an existing hierarchy.
 */
data class {{Command}}(
    override val id: {{Aggregate}}Id,
    // TODO: replace with this command's real payload
    val placeholder: String
) : {{Aggregate}}Command
