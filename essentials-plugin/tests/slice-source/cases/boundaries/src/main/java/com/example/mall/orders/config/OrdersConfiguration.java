package com.example.mall.orders.config;

import com.example.mall.orders.use_cases.place_order.PlaceOrderDecider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrdersConfiguration {
    @Bean
    PlaceOrderDecider placeOrderDecider() {
        return new PlaceOrderDecider();
    }
}
