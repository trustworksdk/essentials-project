package com.example.shop.orders.views.order_list

import com.example.shop.orders.config.ORDER_LIST
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ORDER_LIST)
class OrderListAPI(private val repository: OrderListRepository) {

    /** TRAP: a nullable @RequestParam is optional — a filter, not a discriminator. */
    @GetMapping
    fun list(@RequestParam(required = false) status: String?, @RequestParam limit: Int = 50): List<OrderListView> =
        emptyList()

    /** RULE: Kotlin array syntax for a multi-parameter discriminator. */
    @GetMapping(params = ["from", "to"])
    fun between(@RequestParam from: String, @RequestParam to: String): List<OrderListView> = emptyList()

    /** RULE: value-pinned, Kotlin array syntax. */
    @RequestMapping(method = [RequestMethod.GET], params = ["status=shipped"])
    fun shipped(): List<OrderListView> = emptyList()

    /** RULE: a required @RequestParam binds `?q=` in this handler. */
    @GetMapping("/search")
    fun search(@RequestParam q: String): List<OrderListView> = emptyList()
}
