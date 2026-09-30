package com.acme.shop.orders.views.order_list;

/**
 * The read shape for the order_list view slice — a **closed Spring Data interface projection**.
 *
 * THIS IS THE RESPONSE BODY. There is no `…Response` mirror and no mapper, so §R2's no-adapter rule
 * holds: an interface projection is a *declaration*, not a class that copies fields. Nothing has to
 * be kept in sync with anything.
 *
 * WHY NOT JUST RETURN THE ENTITY? Two reasons, and both are about what the entity is:
 *  - it is a managed, mutable persistence object, so returning it hands a caller a row-backed thing
 *    they can mutate;
 *  - every field of the **write** model would become part of your wire contract, including the ones
 *    added later for an invariant that has nothing to do with this screen.
 * A projection cannot leak a field the API must not expose, because it names only what it selects.
 *
 * Declaring a getter here that the entity does not have fails at startup with a clear message —
 * which is the point: the read shape is checked against the write model rather than drifting from it.
 *
 * Nested projections work through the same mechanism (`getAddress().getCity()`), and a `@Value`
 * SpEL expression can compute a derived field. Prefer plain accessors; a projection that needs much
 * computation is usually a different read model, and therefore a different slice (§R2).
 */
public interface OrderListView {

    String getId();

    // TODO: add the fields this view actually serves, e.g. String getStatus(). Name them exactly as the
    //       entity's properties, or Spring Data cannot resolve them and the context does not start.
}
