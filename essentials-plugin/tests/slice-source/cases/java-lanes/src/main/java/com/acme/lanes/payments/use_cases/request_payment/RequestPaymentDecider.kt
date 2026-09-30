package com.acme.lanes.payments.use_cases.request_payment

import dk.trustworks.essentials.components.kotlin.eventsourcing.Decider

class RequestPaymentDecider : Decider<RequestPayment, Any> {
    override fun handle(cmd: RequestPayment, events: List<Any>): Any? = null
    override fun canHandle(cmd: Any): Boolean = cmd is RequestPayment
}

data class RequestPayment(val paymentId: String)
