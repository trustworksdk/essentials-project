package com.example.shop.orders.entities

import org.springframework.data.annotation.Id

/**
 * Stand-in for the entity a user writes by hand on the service-entity lane (no template ships for
 * it — see entities/CLAUDE.md). It carries exactly the surface the rendered slices call.
 */
class Order(@Id val id: String, placeholder: String) {

    var placeholder: String = placeholder
        private set

    fun applyPlaceholder(newPlaceholder: String): Boolean {
        if (newPlaceholder == placeholder) return false
        placeholder = newPlaceholder
        return true
    }
}
