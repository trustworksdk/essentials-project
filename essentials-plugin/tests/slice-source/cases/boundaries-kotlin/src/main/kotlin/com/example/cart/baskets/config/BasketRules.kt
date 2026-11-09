package com.example.cart.baskets.config

object BasketRules {
    const val MAX_ITEMS = 50
}

fun roundCents(value: Long): Long = value - value % 5
