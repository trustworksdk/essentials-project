package com.example.cart.checkout.entities

import dk.trustworks.essentials.components.document_db.DocumentDbRepository

class Payments(private val docs: DocumentDbRepository<Payment, String>) {
    fun save(payment: Payment) = docs.save(payment)
}
