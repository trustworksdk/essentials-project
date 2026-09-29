package {{packagePath}}.{{bc}}.views.{{view}}

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. **Several query methods are legitimate** when they interrogate
 * the same read shape — filters, sorts, pagination, lookup-by-id. A query serving a *different*
 * purpose over a *different* shape is a different slice. Declare every method in slice.yaml `serves`
 * + `endpoints`.
 *
 * The read shape IS the response (§R2) — return [{{View}}View]. No mirror type, no mapper.
 *
 * Never inject `{{Entity}}Repository` (the BC's write repository), never call `save`/`delete`, never
 * touch another slice's queries.
 */
@RestController
@RequestMapping("{{apiPath}}")
class {{View}}API(private val queries: {{View}}Queries) {

    @GetMapping
    fun {{viewCamel}}(@RequestParam(required = false) status: String): List<{{View}}View> =
        queries.findByStatus(status)

    @GetMapping("/{{{aggregate}}Id}")
    fun by{{Aggregate}}Id(@PathVariable {{aggregate}}Id: String): {{View}}View =
        queries.find{{Aggregate}}By{{Aggregate}}Id({{aggregate}}Id)
            ?: throw NoSuchElementException({{aggregate}}Id)
}
