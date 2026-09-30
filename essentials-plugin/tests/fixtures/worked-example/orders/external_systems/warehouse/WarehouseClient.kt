package {{packagePath}}.orders.external_systems.warehouse

/**
 * Typed outbound port to the warehouse system. It speaks only the warehouse's types.
 *
 * An interface, so [WarehouseTranslator] and [WarehousePublisher] are testable without the warehouse
 * and the transport is swappable without touching the ACL. The transport binding (REST client, SDK,
 * message producer) is the project's and is not part of this example.
 */
interface WarehouseClient {
    fun reserve(request: WarehouseReservationRequest)
}
