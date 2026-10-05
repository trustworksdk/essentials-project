package com.example.ids.billing.views.invoice_lookup

import com.example.ids.billing.types.InvoiceId
import com.example.ids.billing.types.LegacyId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/billing/lookup")
class InvoiceLookupAPI {

    /** TRAP: a value-class id. */
    @Operation(operationId = "invoiceLookupById")
    @GetMapping("/{invoiceId}")
    fun get(@PathVariable invoiceId: InvoiceId): InvoiceLookupView? = null

    /** RULE: a raw String path id. */
    @GetMapping("/raw/{invoiceId}")
    fun raw(@PathVariable invoiceId: String): InvoiceLookupView? = null

    /** RULE: a nullable Long, and a typealias of String — an alias is not a type. */
    @GetMapping(params = ["customerId"])
    fun byCustomer(@RequestParam customerId: Long?, @RequestParam legacyId: LegacyId? = null): List<InvoiceLookupView> =
        emptyList()

    /** TRAP: `status` is not an id. */
    @GetMapping(params = ["status"])
    fun byStatus(@RequestParam status: String): List<InvoiceLookupView> = emptyList()
}
