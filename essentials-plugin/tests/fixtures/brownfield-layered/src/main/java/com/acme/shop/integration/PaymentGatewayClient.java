package com.acme.shop.integration;

import com.acme.shop.integration.dto.GatewayChargeRequest;
import com.acme.shop.integration.dto.GatewayChargeResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;

@Component
public class PaymentGatewayClient {
    private final RestClient client;

    public PaymentGatewayClient(RestClient client) { this.client = client; }

    public String charge(String invoiceId, BigDecimal amount, String cardToken) {
        GatewayChargeRequest request = new GatewayChargeRequest();
        request.merchant_ref = invoiceId;
        request.amount_minor = amount.movePointRight(2).longValue();
        request.card_token = cardToken;
        GatewayChargeResponse response = client.post()
                .uri("https://gw.example.com/v2/charges")
                .body(request)
                .retrieve()
                .body(GatewayChargeResponse.class);
        return response.txn_id;
    }
}
