package {{packagePath}}.orders.external_systems.warehouse

import {{packagePath}}.orders.events.OrderPlaced

/**
 * Anti-corruption layer for the warehouse system — the only place its schema appears.
 *
 * Pure: no Spring, no Essentials, no I/O, so the mapping is unit-testable with neither
 * side running.
 */
class WarehouseTranslator {

    /** Outbound: internal event -> the warehouse's reservation request. */
    fun toReservation(event: OrderPlaced): WarehouseReservationRequest =
        WarehouseReservationRequest(
            reference = event.id.value,
            articleNumber = event.sku,
            units = event.quantity
        )
}

/** The warehouse's request shape — mirrors its contract, not ours. Never used outside this slice. */
data class WarehouseReservationRequest(
    val reference: String,
    val articleNumber: String,
    val units: Int
)
