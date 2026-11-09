package com.example.mall.config;

import com.example.mall.orders.config.OrdersConfiguration;
import com.example.mall.orders.use_cases.place_order.PlaceOrderDecider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(OrdersConfiguration.class)
public class MallConfiguration {
    PlaceOrderDecider decider;
}
