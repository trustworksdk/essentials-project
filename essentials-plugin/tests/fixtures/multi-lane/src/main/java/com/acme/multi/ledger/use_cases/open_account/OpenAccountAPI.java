package com.acme.multi.ledger.use_cases.open_account;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ledger/accounts")
public class OpenAccountAPI {
    private final CommandBus commandBus;

    public OpenAccountAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void openAccount(@RequestBody OpenAccount command) {
        commandBus.send(command);
    }
}
