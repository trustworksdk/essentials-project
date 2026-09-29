package {{packagePath}}.{{bc}}.config

import org.springframework.context.annotation.Configuration

/**
 * Wires the {{Bc}} bounded context — which on the service-entity lane is very nearly nothing.
 *
 * No `@Bean` per decider, because there are no deciders. No `DeciderAndAggregateTypeConfigurator`,
 * no `AggregateType` registration, no `AggregateIdSerializer` — there is no event store to route to.
 *
 * Two things register themselves, and the obligation is to **check** them rather than write them
 * (rules/slice-design.md § Wiring is part of done):
 *
 *  1. `ReactiveHandlersBeanPostProcessor` auto-registers every `CommandHandler` bean with the single
 *     `CommandBus` bean. A `@Component` handler in a scanned package is wired with no code here —
 *     but if `reactive-bean-post-processor-enabled` (default `true`) is switched off in any profile,
 *     **every handler in the application silently stops receiving commands**. That fails no compile
 *     and no unit test, which is why each command slice's IT sends through the bus.
 *  2. Spring Data repositories are registered by scanning.
 *
 * If this class stays empty, that is the lane working as intended — delete it rather than filling it
 * with something that belongs to a slice.
 */
@Configuration
class {{Bc}}Configuration
