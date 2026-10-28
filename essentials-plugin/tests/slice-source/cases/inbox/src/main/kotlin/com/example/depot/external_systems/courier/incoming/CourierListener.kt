package com.example.depot.external_systems.courier.incoming

import com.example.depot.use_cases.flag_lost_parcel.FlagLostParcel
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inbox
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.InboxConfig
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes
import dk.trustworks.essentials.reactive.command.CommandBus
import org.springframework.jms.annotation.JmsListener

data class CourierParcelLost(val parcelId: String)

/**
 * RULE (dispatch): the bus is recognised by its declared type, not its name.
 */
class CourierListener(inboxes: Inboxes, bus: CommandBus) {
    private val inbox: Inbox = inboxes.getOrCreateInbox(InboxConfig.builder().build(), bus)

    @JmsListener(destination = "courier-lost")
    fun on(report: CourierParcelLost) {
        inbox.addMessageReceived(FlagLostParcel(report.parcelId))
    }
}
