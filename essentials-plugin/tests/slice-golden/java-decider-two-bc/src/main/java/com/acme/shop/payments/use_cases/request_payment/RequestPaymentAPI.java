package com.acme.shop.payments.use_cases.request_payment;

import dk.trustworks.essentials.reactive.command.CommandBus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * Single-method endpoint for THIS slice only (rules/slice-design.md §R2).
 *
 * NEVER a multi-endpoint controller injecting many handlers — adding an endpoint means adding a
 * slice with its own API file, never an extra method here.
 *
 * The command IS the contract (§R2) — no adapter layer. {@code RequestPayment} is the {@code @RequestBody}
 * itself: there is no DTO mirroring it, no mapper, and nothing to keep in sync. This also makes
 * {@link RequestPaymentDecider}'s idempotency guard live, because the client supplies the aggregate id and a
 * retry therefore replays the same one instead of generating a fresh id per attempt.
 *
 * REGISTRATION THIS DEPENDS ON — confirm it, do not infer it from a dependency being present:
 * {@code EssentialTypesJacksonModule} ({@code types-jackson3}) must be registered on the WEB
 * {@code JsonMapper}. The Essentials Spring Boot starters publish it as a bean, and Spring Boot adds
 * every {@code JacksonModule} bean to its auto-configured web mapper — so it is there unless the
 * project runs without a starter or replaced Boot's mapper. {@code PaymentId} extends
 * {@code CharSequenceType}, so without that registration the body fails to deserialize.
 *
 * If you have checked and the module is genuinely not on the web mapper, fall back to a small body
 * type carrying the fields the client sends plus a server-generated id — and record that in the
 * slice's CLAUDE.md so it reads as a constraint rather than as habit. See LLM-types-jackson.md
 * § Spring Boot 4 (web mapper).
 *
 * For a typed {@code @PathVariable} (not used here — this slice creates the aggregate) a Java id
 * extending {@code CharSequenceType} needs {@code SingleValueTypeConverter}, registered by importing
 * {@code EssentialsWebMvcConfigurer} / {@code EssentialsWebFluxConfigurer} from
 * {@code types-spring-web}. That module auto-configures nothing — the {@code @Import} IS the
 * registration, and a missing one surfaces as HTTP 500, not 400.
 *
 * Sends the command on the Essentials {@code CommandBus}; the
 * {@code EventStreamDeciderAndAggregateTypeConfigurator} (see payments/config) routes it to
 * {@link RequestPaymentDecider}, loads the event stream, and persists the resulting event.
 */
@RestController
@RequestMapping("/api/payments")
public class RequestPaymentAPI {

    private final CommandBus commandBus;

    public RequestPaymentAPI(CommandBus commandBus) {
        this.commandBus = commandBus;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPayment(@RequestBody RequestPayment command) {
        commandBus.send(command);
    }
}
