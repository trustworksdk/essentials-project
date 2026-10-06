package com.example.shop.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// Trap: TrackingCode needs SingleValueTypeConverter, and config/EssentialsWebConfig imports it.
@RestController
public class TrackingController {

    @GetMapping("/api/tracking/{code}")
    public String byCode(@PathVariable TrackingCode code) {
        return "";
    }

    @GetMapping("/api/tracking/order/{orderId}")
    public String byOrder(@PathVariable OrderId orderId) {
        return "";
    }
}
