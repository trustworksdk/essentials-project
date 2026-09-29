package {{packagePath}}.orders.views.order_list

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read API for THIS view slice only. Further queries over this same read model (filters, sorts,
 * pagination) belong here, not in a new slice — see the essentials plugin's rules/slice-design.md §R2.
 */
@RestController
@RequestMapping("/api/orders")
class OrderListAPI(private val projection: OrderListProjection) {

    @GetMapping
    fun list(): List<OrderListView> = projection.all()
}
