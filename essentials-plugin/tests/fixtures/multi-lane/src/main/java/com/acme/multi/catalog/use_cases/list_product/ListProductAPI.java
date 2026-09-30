package com.acme.multi.catalog.use_cases.list_product;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/products")
public class ListProductAPI {
    private final CommandBus commandBus;

    public ListProductAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void listProduct(@RequestBody ListProduct command) {
        commandBus.send(command);
    }
}
