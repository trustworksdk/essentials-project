package com.example.ids.billing.use_cases.void_invoice

import com.example.ids.billing.types.InvoiceId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** TRAP (6 raw id): `invoiceId: InvoiceId` is typed; `/* @PathVariable invoiceId: String */` is a comment. */
@RestController
@RequestMapping("/api/billing")
class VoidInvoiceAPI {

    @Operation(operationId = "voidInvoice")
    @PostMapping("/{invoiceId}/void")
    fun voidInvoice(@PathVariable invoiceId: InvoiceId) {
        VoidInvoice(invoiceId)
    }
}
