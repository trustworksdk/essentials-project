package com.acme.multi.payments.use_cases.request_payment;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class RequestPaymentAPI {
    private final CommandBus commandBus;

    public RequestPaymentAPI(CommandBus commandBus) { this.commandBus = commandBus; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPayment(@RequestBody RequestPayment command) {
        commandBus.send(command);
    }
}
