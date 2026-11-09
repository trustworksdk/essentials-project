package com.example.cart.baskets.external_systems.storefront

import com.example.cart.baskets.views.basket_list.*
import com.example.cart.kernel.Money

class StorefrontFeed {
    fun row(r: BasketRow, total: Money): String = r.id
}
