package com.example.shop.orders.views.order_list

import com.example.shop.orders.entities.Order
import org.springframework.data.repository.Repository

/**
 * The read-only query interface owned by THIS view slice.
 *
 * On the service-entity lane there is **one** table, shared by the write side and every view, so §R4's
 * ownership rule is restated rather than dropped: a view may read the entity's table, but never
 * through the write repository (rules/slice-design.md § The read side on this lane).
 *
 * Three properties make this legal, and all three are load-bearing:
 *
 *  1. **It extends `Repository`, not `JpaRepository` / `MongoRepository`.** The bare marker interface
 *     exposes *nothing* — no `save`, no `delete`, no `findAll`. The slice gets exactly the methods it
 *     declares, so this interface cannot be used to write even by accident.
 *  2. **It is slice-private**, in the slice directory. Not the BC's write repository, and no other
 *     slice may use it — which is what stops one shared interface accumulating everyone's finders.
 *  3. **It returns [OrderListView], not [Order].** The read shape, never the write model.
 *
 * STRONG CONSISTENCY is this lane's one advantage over a projection: same table, same transaction.
 * Do not add eventual consistency you do not need.
 *
 * PERSISTENCE-NEUTRAL. Interface projections and derived query methods behave identically on Spring
 * Data JPA and Spring Data Mongo — only the entity and the write repository differ per flavour.
 *
 * **DO NOT NAME A LOOKUP HERE `findById`.** The bare marker removes the inherited *methods*; it does
 * not remove the base *implementation*. Spring Data still composes one in (`SimpleJpaRepository` /
 * `SimpleMongoRepository`) and matches a declared method against it by **name and parameter types —
 * the return type is not part of the match**. So `findById` is captured by the base rather than
 * derived as a query: it returns the [Order] entity, the declared projection type is ignored,
 * and the mismatch surfaces as a `ClassCastException` at the call site — not as a wiring error and
 * not at startup. `findOrderById` below derives the same `id = ?` query and does project,
 * which is the only reason it is named that way.
 *
 * The scaffold queries only `id` — the one property the entity contract guarantees
 * (`entities/CLAUDE.md`) — so a freshly scaffolded context starts. Every other derived query names an
 * entity property and fails at startup if the entity has no such property.
 *
 * The same applies to every other base method: `findAll`, `findAllById`, `existsById`, `count`,
 * `getById`, `getReferenceById`. See `rules/slice-design.md` § Spring Data repository surface for the
 * full reserved list.
 */
interface OrderListQueries : Repository<Order, String> {

    // TODO: the queries this slice actually serves, e.g. findByStatus(status: String). Several are fine —
    //       a view slice is scoped by the read model it owns, not by method count (§R2). Declare each in
    //       slice.yaml `serves`. NOT findAll — `findAllBy` derives the same query and projects.
    fun findAllBy(): List<OrderListView>

    // NOT findById — see the reserved-name note above. This name derives the same query and projects.
    fun findOrderById(id: String): OrderListView?
}
