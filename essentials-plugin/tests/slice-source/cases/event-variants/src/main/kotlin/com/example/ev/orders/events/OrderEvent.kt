package com.example.ev.orders.events

sealed interface OrderEvent {
    val id: String
}
