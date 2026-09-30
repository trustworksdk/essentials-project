package com.acme.shop.integration.dto;

public class GatewayChargeRequest {
    public String merchant_ref;
    public long amount_minor;
    public String card_token;
}
