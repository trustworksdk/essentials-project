## Immutable

Base types for immutable Value Objects with reflection-driven equals/hashCode/toString. Maven: `immutable`.

## Package Structure

- `dk.trustworks.essentials.immutable` — core: `Immutable` marker interface, `ImmutableValueObject` abstract base
- `dk.trustworks.essentials.immutable.annotations` — field-level exclusion annotations (`Exclude.ToString`, `Exclude.EqualsAndHashCode`)
- test model in `src/test/java/.../immutable/model/` — example subclasses used by `ImmutableValueObjectTest`

## Key Classes

| Class | Internal Role |
|---|---|
| `Immutable` | Marker interface; shared tag for all immutable types in the project |
| `ImmutableValueObject` | Abstract base; reflection → equals/hashCode/toString; caches results on first call |
| `Exclude` (annotation container) | Holds `@Exclude.ToString` and `@Exclude.EqualsAndHashCode` field annotations |

## Implementation Details

`ImmutableValueObject` uses `Reflector` (from `shared` module) to enumerate instance fields at runtime.

Field selection rules (applied once, then cached in transient fields):
- Skip `static` and `transient` fields always
- `equals`/`hashCode`: skip fields annotated `@Exclude.EqualsAndHashCode`; sort remaining alphabetically; hashCode uses `31 * result + element` reduction (mirrors `Objects.hash`)
- `toString`: skip fields annotated `@Exclude.ToString`; non-null fields first (alpha), null fields last (alpha); format `ClassName { field: value, ... }`
- `equals` rejects subclasses — only `this.getClass().equals(that.getClass())` passes

Caching: `hashCode` (Integer), `toString` (String), `equalsAndHashCodeFields` (List) all stored as `transient` fields → survive object lifetime, not serialization.

## Test Structure

Single test class `ImmutableValueObjectTest` — plain JUnit 5 + AssertJ, no Docker/containers needed.
Test model (`ImmutableOrder`, `OrderId`, `CustomerId`, etc.) lives under `src/test/java` and depends on the `types` module.
Tests cover: equals/hashCode/toString for all-non-null, all-null, and mixed cases; annotation exclusion verified directly.

## Extension Points

No SPIs. Extension = subclass `ImmutableValueObject`, mark all fields `final`, annotate with `@Exclude.*` as needed.
Override `equals`/`hashCode`/`toString` per-class if reflection-based defaults are insufficient.

## Gotchas

- Mutable field types (`List`, `Map`, `Set`) break hashCode/toString caching — result computed once on first call and never recomputed even if mutable contents change.
- `@Exclude.EqualsAndHashCode` excludes from both equals AND hashCode — no way to exclude from only one.
- `equals` is strict on exact class match; `instanceof`-style polymorphic equality intentionally not supported.
- Field ordering in hashCode/toString is alphabetical by field name, not declaration order — counterintuitive when reading constructor signatures.
- `orderLines` field in test model is `@Exclude.EqualsAndHashCode` but NOT `@Exclude.ToString` → still appears in toString output.
- `transient` keyword on a field removes it from ALL three operations (equals, hashCode, toString).
- No validation of immutability at construction time — nothing enforces `final` on subclass fields; convention only.
