# Corrections for the essentials plugin: `types-spring-web`, Jackson 3, and Kotlin

Hand this to whoever maintains the `trustworks-plugins/essentials` plugin. Paths are relative to
`plugins/essentials/`. Every claim below was checked against the Essentials sources and, where it is a
behavioural claim, is now covered by a test named in the text.

## Why this exists

An agent using the plugin reasoned carefully from the bundled docs, reached a conclusion, and reverted a
change that was in fact correct:

> "With a Jackson 3 types module coming for both MVC and Flux, adding `types-spring-web` now is the wrong
> move — and possibly a breaking one. […] Zero benefit, unverified breakage risk, and the proper Jackson 3
> replacement is inbound. Reverted."

The reasoning was sound. Two of its three premises were false, and both came from the plugin's own docs.
The agent's self-diagnosis is worth quoting too, because it names the failure mode exactly:

> "Four errors in this thread all had the same shape — inferring capability from an artifact that only
> partly describes it."

---

## Correction 1 — "Jackson 3 types modules are planned upstream" is false; they shipped

`types-jackson3` and `immutable-jackson3` exist and are the **defaults**. The Essentials root `pom.xml`
sets:

```xml
<essentials.types-jackson.artifactId>types-jackson3</essentials.types-jackson.artifactId>
<essentials.immutable-jackson.artifactId>immutable-jackson3</essentials.immutable-jackson.artifactId>
```

`types-jackson3/src/main/java/dk/trustworks/essentials/jackson/types/EssentialTypesJacksonModule.java`
extends `tools.jackson.databind.module.SimpleModule`. It carries the **same fully qualified name** as the
Jackson 2 one, so only one is ever on the classpath, and `-Pjackson2` selects the other. `jackson3` is
retained as a no-op alias profile.

So on a Spring Boot 4 application the Essentials Jackson module and the web layer are **both Jackson 3**.

Every "planned" / "when they land" / "**Pending:**" clause has to go, and the guidance it gates flips from
*avoid the typed signature* to *use it*:

| File | Location |
|---|---|
| `references/llm/LLM-traps.md` | line 59 — the whole Jackson-2-vs-3 trap including its `**Pending:**` clause |
| `references/llm/LLM-traps.md` | line 71 — "The planned Jackson 3 types modules…" |
| `rules/slice-design.md` | lines 169–172, §R2 |
| `skills/essentials-command-slice/SKILL.md` | lines 89–92 |
| `references/slice/templates/java/command/__Slice__API.java` | lines 22–25 |
| `references/slice/templates/kotlin/command/__Slice__API.kt` | lines 22–24 |
| `IMPLEMENTATION-PLAN.md` | line 126, § Known limitations |
| `CHANGELOG.md` | lines 137–138 |

**Replacement wording:** the Essentials Jackson module matching your Jackson major exists today
(`types-jackson3` for Boot 4, `types-jackson` for Boot 3). What is *still* true, and worth keeping, is that
no Essentials starter registers it on the **web** `ObjectMapper` — the starters configure the persistence
mapper. So "confirm which mapper the module landed on" stays; "wait for a module to be written" goes.

## Correction 2 — the "ships" claim was inverted

`references/llm/LLM-traps.md:71` said the module already ships `WebMvcConfig`, `WebFluxConfig` and
`WebMvcJackson3Config`, and warned against hand-rolling a copy. The same claim appears in
`rules/slice-design.md:169` and `skills/essentials-command-slice/SKILL.md:86-88` ("No hand-written
configurer needed").

Those three classes were in **`src/test`**. `types-spring-web/src/main` contained exactly one class, the
converter. The advice was the precise opposite of the truth: it told readers not to write the only thing
that would have made the feature work.

That also disposes of the `Boot 4 caution`. A test-scope `@Configuration` is never on a consumer's
classpath, so `WebFluxConfig`'s Jackson 2 codec override could not displace anything. The hazard was real
as a *copy-paste template* and imaginary as an auto-configuration.

**This has now been fixed in Essentials rather than only in the docs.** `types-spring-web` ships:

| Class | Behaviour |
|---|---|
| `EssentialsWebMvcConfigurer` | `addFormatters` only |
| `EssentialsWebFluxConfigurer` | `addFormatters` only — deliberately no `configureHttpMessageCodecs` |

Neither is auto-configuration; consumers `@Import` one. `EssentialsWebFluxConfigurerJackson3Test` boots a
Boot 4 reactive context and asserts the JSON codecs come out untouched — unwrapping
`DecoderHttpMessageReader`/`EncoderHttpMessageWriter` to check the actual codec, since the wrapper class
names are Jackson-agnostic and make the naive version of that assertion vacuous.

**Replacement wording:** *`types-spring-web` ships `EssentialsWebMvcConfigurer` and
`EssentialsWebFluxConfigurer`; import one. Neither auto-configures, and neither touches HTTP message
codecs, so adding the module cannot change which Jackson major serialises bodies.* Note the version this
landed in; for older Essentials versions the correct advice is "write your own `WebMvcConfigurer` calling
`registry.addConverter(new SingleValueTypeConverter())`".

## Correction 3 — the Kotlin boundary, with the parts that are counter-intuitive

The agent was right that nothing documented
`dk.trustworks.essentials.kotlin.types.StringValueType`, and right that
`EssentialTypesJacksonModule` does not cover it. But the full picture is narrower and stranger than
"Kotlin is unsupported", and the plugin should state the tested version rather than a plausible one — the
plugin scaffolds a Kotlin template family whose ids are `@JvmInline value class`, so this is directly
load-bearing.

### Path variables

| Kotlin shape | Binds as `@PathVariable`? | Why |
|---|---|---|
| `@JvmInline value class` over anything | **yes, with nothing from Essentials** | Kotlin *unboxes* a value class in every JVM signature, nullable included. `fun byOrderId(orderId: OrderId)` compiles to `byOrderId-GEJpfBY(String)` — verify with `javap -p`. Spring only ever sees `String`, binds it natively, Kotlin re-wraps at the call boundary |
| non-inline class wrapping a `String` | **yes**, via Spring's own `ObjectToObjectConverter` | it finds the single `String`-arg constructor |
| non-inline class wrapping anything else | only with `KotlinValueTypeConverter` | no `String`-arg constructor for Spring to find |

The practical consequence for the plugin: **a Kotlin template can type its `@PathVariable` as a value-class
id today and always could**, on any Essentials version, with no `types-spring-web` dependency at all. The
caution in the Kotlin template is unnecessary.

`types-spring-web` now also ships `KotlinValueTypeConverter` for the third row, registered behind a
`ClassUtils.isPresent` guard because `kotlin-reflect` is an `<optional>` dependency. Tests:
`KotlinValueTypeBindingTest`, `KotlinValueTypeConverterRequiredTest`.

Worth propagating as a diagnostic hint: a missing converter surfaces as **HTTP 500**
(`ConversionNotSupportedException` — Spring classes it as server misconfiguration), not 400. A forgotten
`@Import` therefore looks like a server bug on a well-formed request.

### Request bodies

Neither `EssentialTypesJacksonModule` flavour references `dk.trustworks.essentials.kotlin.types` at all.
Kotlin types need `jackson-module-kotlin`'s `KotlinModule` on the web mapper.

The failure mode is **silent, and it is a wire-format change**:

```
without KotlinModule:  {"value":"order-4711"}
with KotlinModule:     "order-4711"
```

Nothing throws on the way out. A service that persists or publishes the first form has changed its
contract with no error to notice. Asserted on both majors by `KotlinJacksonBodyJackson2Test` /
`KotlinJacksonBodyJackson3Test`.

Also correct `references/llm/LLM-types-integrations.md:42`, which claims "✅ All types" for Spring Web.

## Correction 4 — reinstate the reverted change

Once Corrections 1–2 are in, the revert recorded in `IMPLEMENTATION-PLAN.md` § Known limitations should be
undone rather than left pending:

- add `types-spring-web` to `references/template/backend/pom.xml`, **and** scaffold a configurer importing
  `EssentialsWebMvcConfigurer`/`EssentialsWebFluxConfigurer` — the dependency alone does nothing, which is
  the trap that produced the original confusion;
- flip `__Slice__API` in both language template families to `@RequestBody {{Command}}`, deleting
  `{{Slice}}Request`;
- flip `CancelOrderAPI` to a typed `@PathVariable`;
- update `PlaceOrderAPI` in the worked example — which, as that document already notes, makes
  `PlaceOrderDecider`'s idempotency guard live, since a retry replays a client-supplied id.

Per Correction 3, the **Kotlin** templates can take a typed value-class `@PathVariable` immediately and
independently of any of this.

## A process note worth adding to the plugin's own guidance

All four errors share one shape, and the specific instance is cheap to guard against:

> **Before describing a class from a doc snippet as shipped API, check whether the file is under `src/main`
> or `src/test`.**

That single check would have caught two of the four. It generalises to the rule the plugin already
gestures at — verify a registration before typing against it — but it is worth stating concretely, because
"the module ships `WebFluxConfig`" is exactly the kind of claim that reads as verified when it is not.

A second, subtler one from this work: an assertion that *cannot fail* is worse than no assertion. The first
version of the codec test inspected `DecoderHttpMessageReader` wrapper class names for the string
`Jackson2Json`, which they never contain regardless of the codec inside — it passed, and proved nothing.
`isNotEmpty()` on the filtered list is what makes it real.
