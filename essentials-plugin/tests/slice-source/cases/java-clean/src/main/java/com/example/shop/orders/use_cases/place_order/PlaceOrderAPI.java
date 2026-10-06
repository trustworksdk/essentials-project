package com.example.shop.orders.use_cases.place_order;

import com.example.shop.orders.config.ApiPaths;
import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TRAP (gate 6): the class-level {@code @RequestMapping} is the base route and is not a second mapping,
 * and neither is this one: {@code @GetMapping("/phantom")}.
 */
@RestController
@RequestMapping(ApiPaths.ORDERS)
public class PlaceOrderAPI {
    private final CommandBus commandBus;

    public PlaceOrderAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @PostMapping
    public void placeOrder(@RequestBody PlaceOrder command) {
        commandBus.send(command);
    }
}
