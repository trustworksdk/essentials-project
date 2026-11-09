package com.example.cart.checkout.entities

import com.example.cart.baskets.use_cases.open_basket.OpenBasket

class Payment(var basket: String) {
    fun reopen(): OpenBasket? = null
}
