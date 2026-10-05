package com.example.golden.shipping

import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ShipmentLookup {

    @GetMapping("/api/shipments/legacy/{ref}")
    fun byLegacyRef(@PathVariable ref: LegacyRef, @RequestParam ticket: Ticket?): String = ""

    @Operation(operationId = "shipmentById")
    @GetMapping("/api/shipments/{id}")
    fun byId(@PathVariable id: ShipmentId): String = ""
}
