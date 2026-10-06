package com.example.shop.orders.views.order_board

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/orders/board")
class OrderBoardAPI {

    @GetMapping
    fun board(): List<String> = emptyList()

    /** FINDING (6 discriminator): `status` is bound here, on /filtered — not on the declared route. */
    @GetMapping("/filtered", params = ["status"])
    fun filtered(@RequestParam status: String): List<String> = emptyList()

    /** FINDING (6 discriminator): pinned to `open`; the manifest pins `closed`. */
    @GetMapping(params = ["state=open"])
    fun open(): List<String> = emptyList()

    /** RULE: `?from=&to=` bound together in this one handler. */
    @GetMapping(params = ["from", "to"])
    fun between(@RequestParam from: String, @RequestParam to: String): List<String> = emptyList()
}
