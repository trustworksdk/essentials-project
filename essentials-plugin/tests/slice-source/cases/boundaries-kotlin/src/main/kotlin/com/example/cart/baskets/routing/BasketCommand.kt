package com.example.cart.baskets.routing

import com.example.cart.baskets.types.BasketId

interface BasketCommand {
    val id: BasketId
}
