package {{packagePath}}.{{bc}}.views.{{view}}

/**
 * The read shape for the {{view}} view slice — a **closed Spring Data interface projection**.
 *
 * THIS IS THE RESPONSE BODY. No `…Response` mirror and no mapper, so §R2's no-adapter rule holds: an
 * interface projection is a *declaration*, not a class that copies fields.
 *
 * It is an `interface`, not a `data class`, on purpose. A data-class (DTO) projection forces Spring
 * Data to materialise every constructor parameter and cannot be nested; the closed interface selects
 * only what it names and fails at startup if it names a property the entity does not have — which is
 * the check that keeps the read shape from drifting off the write model.
 *
 * WHY NOT JUST RETURN THE ENTITY? It is a managed, mutable persistence object, and every field of the
 * **write** model would become part of your wire contract — including fields added later for an
 * invariant that has nothing to do with this screen.
 *
 * Use JavaBean-style getter names: Spring Data resolves `getStatus()` against the entity's `status`
 * property. In Kotlin, `val status: String` on the interface generates exactly that. The scaffold names
 * only `id`, the one property the entity contract guarantees, so a fresh context starts.
 */
interface {{View}}View {

    val id: String

    // TODO: add the fields this view actually serves, e.g. `val status: String`. Name them exactly as the
    //       entity's properties, or Spring Data cannot resolve them and the context does not start.
}
