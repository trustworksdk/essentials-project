package {{packagePath}}.{{bc}}.views.{{view}};

import dk.trustworks.essentials.components.document_db.DocumentDbRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 *
 * One API file, owned by this slice. One query method by default — add more when they interrogate
 * this slice's OWN read model (filters, sorts, pagination, lookup-by-id). A query serving a
 * different purpose over a different read-model shape is a different slice; needing one more event
 * is NOT — that is this slice evolving (§ Evolving a view slice).
 *
 * The read model IS the response (§R2) — return the view entity. No mirror response type, no mapper.
 *
 * Declare every method you add in slice.yaml {@code serves} + {@code endpoints}.
 *
 * Never touches the event store, never calls a Decider, never reads another slice's repository.
 */
@RestController
@RequestMapping("{{apiPath}}")
public class {{View}}API {

    private final DocumentDbRepository<{{View}}View, String> repository;

    public {{View}}API(DocumentDbRepository<{{View}}View, String> repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<{{View}}View> {{viewCamel}}() {
        return repository.findAll();
    }
}
