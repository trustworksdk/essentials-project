package {{packagePath}}.{{bc}}.views.{{view}};

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. **Several query methods are legitimate** when they interrogate
 * the same read shape — filters, sorts, pagination, lookup-by-id. A query serving a *different*
 * purpose over a *different* shape is a different slice. Declare every method in slice.yaml `serves`
 * + `endpoints`.
 *
 * The read shape IS the response (§R2) — return {@link {{View}}View}. No mirror type, no mapper.
 *
 * Never inject `{{Entity}}Repository` (the BC's write repository), never call `save`/`delete`, never
 * touch another slice's queries. This slice reads its own table through its own interface, and that
 * is the entire allowance.
 */
@RestController
@RequestMapping("{{apiPath}}")
public class {{View}}API {

    private final {{View}}Queries queries;

    public {{View}}API({{View}}Queries queries) {
        this.queries = queries;
    }

    @GetMapping
    public List<{{View}}View> {{viewCamel}}() {
        return queries.findAllBy();
    }

    @GetMapping("/{{{aggregate}}Id}")
    public {{View}}View by{{Aggregate}}Id(@PathVariable String {{aggregate}}Id) {
        return queries.find{{Aggregate}}ById({{aggregate}}Id).orElseThrow();
    }
}
