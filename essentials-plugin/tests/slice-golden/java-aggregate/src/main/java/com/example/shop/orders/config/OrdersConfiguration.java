package com.example.shop.orders.config;

import org.springframework.context.annotation.Configuration;

/**
 * Wires the Orders bounded context — which on the aggregate lane is nearly nothing, and that is the
 * lane working as intended.
 *
 * COMPARE THE DECIDER LANE, which needs a {@code @Bean} per Decider plus an
 * {@code EventStreamDeciderAndAggregateTypeConfigurator} to route commands by aggregate type. Here
 * there is no routing to configure: each slice's handler loads the aggregate by id through
 * {@link com.example.shop.orders.aggregates.Orders} and calls a method on it, so there is no
 * command-type-to-stream mapping for the framework to resolve. That is also why this BC has no
 * {@code routing/} package.
 *
 * Two things register themselves, and the obligation is to **check** them rather than write them
 * (rules/slice-design.md § Wiring is part of done):
 *
 *  1. {@code ReactiveHandlersBeanPostProcessor} auto-registers every {@code CommandHandler} bean with
 *     the single {@code CommandBus} bean, so a {@code @Component} handler in a scanned package is
 *     wired with no code here. If {@code reactive-bean-post-processor-enabled} (default {@code true})
 *     is switched off in any profile, **every handler in the application silently stops receiving
 *     commands** — no compile error, no failing unit test. That is why each command slice's IT sends
 *     through the bus rather than calling the handler directly.
 *  2. {@code Orders} is a {@code @Component} and owns the {@code AggregateType} constant, so
 *     the aggregate's stream name is declared next to the repository that uses it rather than here.
 *
 * IF THIS BC USES {@code @AggregateSnapshotPolicy} OR {@code @AggregateClosingBooksPolicy}, THIS IS
 * WHERE THE DECLARATION GOES. Those annotations are registered by {@code BeanPostProcessor}s, which
 * observe only Spring beans — and an aggregate root is not a Spring bean and never should be. An
 * annotation on {@code Order} therefore reaches no registry and **nothing complains**. Declare
 * it explicitly:
 *
 * <pre>{@code
 * @Bean
 * EssentialsAggregateDeclarations ordersAggregates() {
 *     return EssentialsAggregateDeclarations.builder()
 *                                           .declare(Orders.AGGREGATE_TYPE, Order.class)
 *                                           .build();
 * }
 * }</pre>
 *
 * If this class stays empty, delete it rather than filling it with something that belongs to a slice.
 */
@Configuration
public class OrdersConfiguration {
}
