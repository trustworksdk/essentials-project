# types-springdata-mongo

Spring Data MongoDB persistence bridge for `SingleValueType` from the `types` module. Maven: `types-springdata-mongo`.

## Package Structure

Single package: `dk.trustworks.essentials.types.springdata.mongo`
- Main: 2 classes — converter + id generator
- Test model under `…mongo.model`: `Order`, `Product`, custom `SingleValueType` subtypes used as realistic fixtures

## Key Classes

| Class | Role |
|---|---|
| `SingleValueTypeConverter` | `GenericConverter` — registered in `MongoCustomConversions`; handles `SingleValueType` ↔ BSON types |
| `SingleValueTypeRandomIdGenerator` | `BeforeConvertCallback<Object>` — reflects on `@Id` field, calls `static random()` on `SingleValueType` when field is null |

## Conversion Table (inside `SingleValueTypeConverter`)

| Java type | BSON/Mongo type | Note |
|---|---|---|
| `CharSequenceType` | `String` | default |
| `CharSequenceType` (ObjectId-valued) | `ObjectId` | must be passed explicitly in constructor |
| `NumberType` | `Number` / `Decimal128` | auto-unwraps `Decimal128` → `BigDecimal` |
| `LocalDateTimeType` / `LocalDateType` / `InstantType` / `LocalTimeType` | `Date` | UTC hardcoded in all branches |
| `SingleValueType` as Map key | `Object` | explicit `ConvertiblePair(SingleValueType.class, Object.class)` |
| `OffsetDateTimeType` / `ZonedDateTimeType` | — | **not supported** — Spring Data MongoDB limitation |

## Test Structure

- `@DataMongoTest` + `@Testcontainers` — real MongoDB via `MongoDBContainer("mongo:latest")`
- `@DirtiesContext(AFTER_CLASS)` per IT class
- `TypesSpringDataMongoApplicationTests` — `@SpringBootApplication` config fixture; wires `SingleValueTypeRandomIdGenerator` bean and `MongoCustomConversions` with `useSpringDataJavaTimeCodecs()`
- Two IT classes: `OrderRepositoryIT` (complex aggregate with Map keys, temporal types), `ProductRepositoryIT` (also exercises `MongoTemplate` querying with `SingleValueType` criteria)

## Extension Points

No explicit SPIs. Extension path: add new `ConvertiblePair` entries in `SingleValueTypeConverter.getConvertibleTypes()` and matching branches in `convert()`.

## Gotchas

- **ObjectId map keys**: `CharSequenceType` whose `random()` returns an `ObjectId.toString()` string (e.g. `ProductId`) MUST be listed explicitly in the `SingleValueTypeConverter` constructor — otherwise Mongo stores/reads `String` and map-key deserialization breaks silently.
- **`random()` method required**: `SingleValueTypeRandomIdGenerator` calls `static random()` via reflection (`Reflector.invokeStatic`). Missing or non-static `random()` → runtime exception on first save of null-id entity.
- **UTC hardcoded**: All `Date` → temporal-type conversions use `ZoneId.of("UTC")` directly, not `ZoneOffset.UTC` constant. Timezone is not configurable.
- **Codec choice matters**: Tests use `useSpringDataJavaTimeCodecs()` (commented-out alternative is `useNativeDriverJavaTimeCodecs()`). Mixing codecs with the converter causes duplicate conversion — pick one per app context.
- **`spring-data-mongodb` is `provided` scope** — consumers must bring their own compatible version.
- Module is marked **WORK-IN-PROGRESS** in LLM docs.
