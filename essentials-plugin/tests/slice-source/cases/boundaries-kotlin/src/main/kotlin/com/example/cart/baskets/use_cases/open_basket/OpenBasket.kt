package com.example.cart.baskets.use_cases.open_basket

import com.example.cart.baskets.routing.BasketCommand
import com.example.cart.baskets.types.BasketId

data class OpenBasket(override val id: BasketId) : BasketCommand
