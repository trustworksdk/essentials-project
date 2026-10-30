package com.example.cart.baskets.events

import com.example.cart.baskets.types.BasketId

sealed interface BasketEvent {
    val id: BasketId
}

data class BasketOpened(override val id: BasketId) : BasketEvent
