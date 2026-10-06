package {{packagePath}}.orders.views.order_list

import {{packagePath}}.orders.types.OrderStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Read API for THIS view slice only (rules/slice-design.md §R2).
 */
@RestController
@RequestMapping("/api/orders")
class OrderListAPI(private val repository: OrderListRepository) {

    @GetMapping
    fun list(): List<OrderListView> = repository.findAll().sortedBy { it.orderId.value }

    @GetMapping(params = ["status"])
    fun byStatus(@RequestParam status: OrderStatus): List<OrderListView> = repository.findByStatus(status)
}
