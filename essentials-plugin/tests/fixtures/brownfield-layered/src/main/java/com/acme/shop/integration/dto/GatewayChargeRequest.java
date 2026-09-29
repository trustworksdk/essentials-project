package com.acme.shop.integration.dto;

// Foreign schema — snake_case, minor units. Not our domain vocabulary.
public class GatewayChargeRequest {
    public String merchant_ref;
    public long amount_minor;
    public String card_token;
}
