package com.acme.shop.orders.config;

/** Route constants. RULE: a mapping path given as a constant is resolved through this index. */
public final class ApiPaths {
    public static final String ORDERS = "/api/orders";
    public static final String ORDER_LIST = ORDERS + "/list";

    private ApiPaths() {
    }
}
