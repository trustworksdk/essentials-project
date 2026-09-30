package com.acme.shop

import com.acme.shop.orders.external_systems.warehouse.WarehouseClient
import com.acme.shop.orders.external_systems.warehouse.WarehouseReservationRequest
import org.springframework.stereotype.Component

/**
 * CI-only: the adapter behind the worked example's outbound port. The fixture ships the port and no adapter (an
 * adapter is infrastructure, not slice code), so without this the context cannot start. Test sources only, so it
 * never reaches the fixture itself; component-scanned because the host's application lives in `com.acme.shop`.
 */
@Component
class StubWarehouseClient : WarehouseClient {
    override fun reserve(request: WarehouseReservationRequest) {}
}
