package {{packagePath}}.orders.views.order_list

import {{packagePath}}.orders.events.OrderCancelled
import {{packagePath}}.orders.events.OrderEvent
import {{packagePath}}.orders.events.OrderPlaced
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * VIEW slice — projects `OrderEvent`s into the [OrderListView] read model.
 *
 * `project(event)` below is the pure projection logic for this slice. The
 * SUBSCRIPTION wiring (how events reach it) is intentionally illustrative here:
 * in a real Essentials project register this as an `InTransactionEventProcessor`
 * (synchronous read model) or a `ViewEventProcessor` (async, polling) and persist
 * to PostgreSQL DocumentDb / JDBI rather than this in-memory map. Consult the
 * `essentials-docs` skill (`LLM-kotlin-eventsourcing.md`, `LLM-postgresql-document-db.md`)
 * for the processor + store wiring. The in-memory store keeps this teaching example
 * self-contained and compilable.
 */
@Component
class OrderListProjection {
    private val rows = ConcurrentHashMap<String, OrderListView>()

    fun project(event: OrderEvent) {
        when (event) {
            is OrderPlaced -> rows[event.id.value] =
                OrderListView(event.id.value, event.sku, event.quantity, "PLACED")
            is OrderCancelled -> rows.computeIfPresent(event.id.value) { _, v -> v.copy(status = "CANCELLED") }
        }
    }

    fun all(): List<OrderListView> = rows.values.sortedBy { it.orderId }
}
