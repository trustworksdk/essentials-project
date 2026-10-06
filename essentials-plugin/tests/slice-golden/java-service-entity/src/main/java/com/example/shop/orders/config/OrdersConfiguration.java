package com.example.shop.orders.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the Orders bounded context — which on the service-entity lane is very nearly nothing.
 *
 * There is no {@code @Bean} per decider here, because there are no deciders. There is no
 * {@code EventStreamDeciderAndAggregateTypeConfigurator}, no {@code AggregateType} registration and
 * no {@code AggregateIdSerializer}, because there is no event store to route to.
 *
 * Two things register themselves, and the obligation is to **check** them rather than write them
 * (rules/slice-design.md § Wiring is part of done):
 *
 *  1. {@code ReactiveHandlersBeanPostProcessor} auto-registers every {@code CommandHandler} bean with
 *     the single {@code CommandBus} bean. So a {@code @Component} handler in a scanned package is
 *     wired with no code here — but if {@code reactive-bean-post-processor-enabled} (default
 *     {@code true}) is switched off in any profile, **every handler in the application silently
 *     stops receiving commands**. That fails no compile and no unit test, which is why each command
 *     slice's IT sends through the bus rather than calling the handler directly.
 *  2. Spring Data repositories are registered by scanning.
 *
 * This class therefore exists mostly as the place for genuinely BC-scoped wiring when it appears —
 * a {@code @Transactional} qualifier, an Inbox/Outbox configuration for this context's integration,
 * an entity-scan override. If it stays empty, that is the lane working as intended; delete it rather
 * than filling it with something that belongs to a slice.
 */
@Configuration
public class OrdersConfiguration {
}
