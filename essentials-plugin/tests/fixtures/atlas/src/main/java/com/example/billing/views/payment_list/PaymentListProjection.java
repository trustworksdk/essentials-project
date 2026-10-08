package com.example.billing.views.payment_list;

import com.example.billing.events.PaymentCharged;
import com.example.billing.events.PaymentRefunded;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;

public class PaymentListProjection {

    @MessageHandler
    void on(PaymentCharged event) {
    }

    @MessageHandler
    void on(PaymentRefunded event) {
    }
}
