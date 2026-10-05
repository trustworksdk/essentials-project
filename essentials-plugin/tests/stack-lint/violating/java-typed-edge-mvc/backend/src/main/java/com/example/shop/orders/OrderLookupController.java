package com.example.shop.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
public class OrderLookupController {

    @GetMapping("/by-number/{number}")
    public String byNumber(@PathVariable("number") OrderNumber number,
                           @RequestParam Optional<TrackingCode> tracking) {
        return "";
    }

    @GetMapping("/by-bin/{bin}")
    public String byBin(@PathVariable final BinCode bin) {
        return "";
    }

    // Trap: @PathVariable TrackingCode code — a comment, not a parameter.
    @GetMapping("/{orderId}")
    public String byId(@PathVariable OrderId orderId, @RequestParam(required = false) SkuCode sku) {
        var note = "@RequestParam TrackingCode tracking";
        return note;
    }
}
