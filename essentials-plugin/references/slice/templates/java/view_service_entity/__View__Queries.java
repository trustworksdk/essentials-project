package {{packagePath}}.{{bc}}.views.{{view}};

import {{packagePath}}.{{bc}}.entities.{{Entity}};
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The read-only query interface owned by THIS view slice.
 *
 * On the service-entity lane there is **one** table, shared by the write side and every view, so §R4's
 * ownership rule is restated rather than dropped: a view may read the entity's table, but never
 * through the write repository (rules/slice-design.md § The read side on this lane).
 *
 * Three properties make this legal, and all three are load-bearing:
 *
 *  1. **It extends {@code Repository}, not {@code JpaRepository} / {@code MongoRepository}.** The bare
 *     marker interface exposes *nothing* — no {@code save}, no {@code delete}, no {@code findAll}.
 *     The slice gets exactly the methods it declares, so this interface cannot be used to write even
 *     by accident. Extending the CRUD interface instead would hand every view a write path.
 *  2. **It is slice-private**, in the slice directory. It is not the BC's write repository and no
 *     other slice may use it, which is what stops one shared interface accumulating everyone's
 *     finders — the failure this rule exists to prevent.
 *  3. **It returns {@link {{View}}View}, not {@link {{Entity}}}.** The read shape, never the write model.
 *
 * STRONG CONSISTENCY is this lane's one advantage over a projection: same table, same transaction, so
 * a read after a write sees it. Do not add eventual consistency you do not need.
 *
 * PERSISTENCE-NEUTRAL. Interface projections and derived query methods behave identically on Spring
 * Data JPA and Spring Data Mongo, so this file is the same either way — only the entity and the write
 * repository differ per flavour.
 *
 * <p><strong>DO NOT NAME A LOOKUP HERE {@code findById}.</strong> The bare marker removes the
 * inherited <em>methods</em>; it does not remove the base <em>implementation</em>. Spring Data still
 * composes one in ({@code SimpleJpaRepository} / {@code SimpleMongoRepository}) and matches a declared
 * method against it by <strong>name and parameter types — the return type is not part of the
 * match</strong>. So {@code findById} is captured by the base rather than derived as a query: it
 * returns the {@link {{Entity}}} entity, the declared projection type is ignored, and the mismatch
 * surfaces as a {@code ClassCastException} at the call site — not as a wiring error and not at
 * startup. {@code find{{Aggregate}}By{{Aggregate}}Id} below derives the same {@code id = ?} query and
 * does project, which is the only reason it is named that way.
 *
 * <p>The same applies to every other base method: {@code findAll}, {@code findAllById},
 * {@code existsById}, {@code count}, {@code getById}, {@code getReferenceById}. See
 * {@code rules/slice-design.md} § Spring Data repository surface for the full reserved list.
 */
public interface {{View}}Queries extends Repository<{{Entity}}, String> {

    // TODO: the queries this slice actually serves. Several are fine — a view slice is scoped by the
    //       read model it owns, not by method count (§R2). Declare each in slice.yaml `serves`.
    List<{{View}}View> findByStatus(String status);

    // NOT findById — see the reserved-name note above. This name derives the same query and projects.
    Optional<{{View}}View> find{{Aggregate}}By{{Aggregate}}Id(String {{aggregate}}Id);
}
