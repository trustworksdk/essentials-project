## types-spring-web

Spring WebMvc/WebFlux `GenericConverter` enabling `SingleValueType` subtypes as `@PathVariable`/`@RequestParam`. Maven: `types-spring-web`.

## Package Structure

- `dk.trustworks.essentials.types.spring.web` — single production class (`SingleValueTypeConverter`)
- `...spring.web` (test) — Spring Boot test app configs (`WebMvcConfig`, `WebFluxConfig`, `WebMvcSpringWebApplication`)
- `...spring.web.controllers` (test) — `WebMvcController`, `WebFluxController` covering all supported type combos
- `...spring.web.model` (test) — domain value types used across tests (`OrderId`, `CustomerId`, `DueDate`, etc.)

## Key Classes

| Class | Role |
|---|---|
| `SingleValueTypeConverter` | `GenericConverter` — sole production class; handles all `String`/`Number` → `SingleValueType` conversion |
| `WebMvcConfig` (test) | Registers converter via `FormatterRegistry`; template for WebMvc users |
| `WebFluxConfig` (test) | Registers converter + wires Jackson codecs; template for WebFlux users |
| `WebMvcSpringWebApplication` (test) | Boot entry point; registers `EssentialTypesJacksonModule` bean |

## Test Structure

No Docker/infrastructure needed — pure Spring Boot in-memory tests.

- `WebMvcControllerTest` — `@SpringBootTest` + `MockMvc`; covers `@PathVariable` and `@RequestParam` for all `JSR310SingleValueType` subtypes plus `CharSequenceType`/`NumberType`
- `WebFluxControllerIT` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `WebTestClient`; mirrors WebMvc tests over reactive stack
- Test model classes (`OrderId`, `DueDate`, `TransactionTime`, etc.) are standalone implementations — not shared with other modules

## Extension Points

No SPI. `SingleValueTypeConverter` is `final`. Extension = registering converter in your own `WebMvcConfigurer`/`WebFluxConfigurer`.

`SingleValueType.fromObject(value, targetType)` (from `types` module) drives all instantiation — target type must have constructor accepting wrapped value.

## Gotchas

- Converter scope: `@PathVariable` + `@RequestParam` only. `@RequestBody`/`@ResponseBody` → handled by `types-jackson` (`EssentialTypesJacksonModule`), not this module.
- `ZonedDateTimeType` path variables must be URL-encoded by client. Converter auto-decodes via `URLDecoder.decode(source, UTF_8)`. Other temporal types do NOT need encoding.
- `NumberType` conversion uses `NumberType.resolveNumberClass()` to pick the concrete number class (`Long`, `BigDecimal`, etc.) before parsing — target type hierarchy determines the number class, not the string content.
- `spring-web` and `spring-webmvc`/`spring-webflux` are `provided` scope — caller's app must supply them. Only `spring-core` is `provided` at compile time.
- `types-jackson` is a compile dependency (not test-only) — `EssentialTypesJacksonModule` must be registered separately as a bean for JSON body handling.
- Both WebMvc and WebFlux share the same converter class — no separate implementations per stack.
