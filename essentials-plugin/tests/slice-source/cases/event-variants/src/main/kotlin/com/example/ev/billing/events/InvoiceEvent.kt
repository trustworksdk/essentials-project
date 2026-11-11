package com.example.ev.billing.events

// variants nested in the sealed parent: splitting renames InvoiceEvent$InvoiceIssued
sealed interface InvoiceEvent {
    data class InvoiceIssued(val id: String) : InvoiceEvent
    data class InvoiceVoided(val id: String) : InvoiceEvent

    companion object {
        const val TYPE = "invoice"
    }
}
