package com.acme.lanes.payments.use_cases.capture_payment

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

class CapturePaymentDecider : Decider<CapturePayment, Any> {
    override fun handle(cmd: CapturePayment, events: List<Any>): Any? = null
    override fun canHandle(cmd: Any): Boolean = cmd is CapturePayment
}

data class CapturePayment(val paymentId: String)
