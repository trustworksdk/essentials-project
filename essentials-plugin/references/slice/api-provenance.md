# API provenance ledger

Every Trustworks Essentials symbol named by a slice template, and the bundled doc under
`references/llm/` that proves it exists. This is the mechanised form of the plugin's hardest
invariant: **never invent an Essentials API**.

A symbol may not appear in a template until it appears here, and it may not appear here until it
appears in a doc that traces to upstream source.

This ledger proves that a name is documented. Whether the rendered code compiles against the framework
is proved separately: `scripts/render-slice.py check` renders every template family into the goldens under
`tests/slice-golden/`, and the repository's `scripts/plugin-scaffold.sh slices` compiles those
compositions on hosts rendered by `/essentials:init`'s own renderer. Both are needed. A symbol can be
documented and still be called with the wrong signature, and a template can compile against a symbol
no doc explains.

## Verify

```bash
cd essentials-plugin
python3 - <<'EOF'
import re, pathlib, glob
files=[f for f in glob.glob('references/slice/templates/**/*', recursive=True) if pathlib.Path(f).is_file()]
docs="\n".join(pathlib.Path(p).read_text() for p in glob.glob('references/llm/*.md'))
bad=[]
for f in files:
    for imp in re.findall(r'^import (?:static )?(dk\.trustworks\.essentials[\w.]*)', pathlib.Path(f).read_text(), re.M):
        if imp.split('.')[-1] not in docs: bad.append((f, imp))
print("UNPROVEN:", bad or "none")
EOF
```

## Ledger

| Symbol | Languages | Fully-qualified name | Proven in |
|---|---|---|---|
| `DelegatingDocumentDbRepository` | kotlin | `dk.trustworks.essentials.components.document_db.DelegatingDocumentDbRepository` | `LLM-postgresql-document-db.md` |
| `DocumentDbRepository` | java | `dk.trustworks.essentials.components.document_db.DocumentDbRepository` | `LLM-components.md` |
| `DocumentDbRepositoryFactory` | java+kotlin | `dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory` | `LLM-postgresql-document-db.md` |
| `JavaVersionedEntity` | java | `dk.trustworks.essentials.components.document_db.JavaVersionedEntity` | `LLM-components.md` |
| `Version` | java+kotlin | `dk.trustworks.essentials.components.document_db.Version` | `LLM-components.md` |
| `VersionedEntity` | kotlin | `dk.trustworks.essentials.components.document_db.VersionedEntity` | `LLM-components.md` |
| `DocumentEntity` | java+kotlin | `dk.trustworks.essentials.components.document_db.annotations.DocumentEntity` | `LLM-postgresql-document-db.md` |
| `Id` | java+kotlin | `dk.trustworks.essentials.components.document_db.annotations.Id` | `LLM-components.md` |
| `Indexed` | java+kotlin | `dk.trustworks.essentials.components.document_db.annotations.Indexed` | `LLM-postgresql-document-db.md` |
| `DbType` | java | `dk.trustworks.essentials.components.document_db.postgresql.DbType` | `LLM-postgresql-document-db.md` |
| `AggregateRoot` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.modern.AggregateRoot` | `LLM-eventsourced-aggregates.md` |
| `EventHandler` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler` | `LLM-eventsourced-aggregates.md` ⚠️ **usage only — see note below** |
| `StatefulAggregateRepository` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository` | `LLM-eventsourced-aggregates.md` |
| `StatefulAggregateInstanceFactory` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory` (static `reflectionBasedAggregateRootFactory()`) | `LLM-eventsourced-aggregates.md` |
| `SeparateTablePerAggregateEventStreamConfiguration` | java | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration` | `LLM-postgresql-event-store.md` |
| `FailFast` | java | `dk.trustworks.essentials.shared.FailFast` (static `requireNonNull`) | `LLM-shared.md` |
| `EventStreamAggregateTypeConfiguration` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration` | `LLM-eventsourced-aggregates.md` |
| `EventStreamDecider` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDecider` | `LLM-components.md` |
| `HandlesCommandsThatInheritFromCommandType` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType` | `LLM-eventsourced-aggregates.md` |
| `EventStreamEvolver` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamEvolver` | `LLM-components.md` |
| `EventStreamDeciderAndAggregateTypeConfigurator` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.adapters.EventStreamDeciderAndAggregateTypeConfigurator` | `LLM-eventsourced-aggregates.md` |
| `GivenWhenThenScenario` | java | `dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario` | `LLM-eventsourced-aggregates.md` |
| `ConfigurableEventStore` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore` | `LLM-eventsourced-aggregates.md` |
| `AggregateType` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType` | `LLM-components.md` |
| `EventProcessor` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor` | `LLM-components.md` |
| `EventProcessorDependencies` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies` | `LLM-postgresql-event-store.md` |
| `ViewEventProcessor` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessor` | `LLM-kotlin-eventsourcing.md` |
| `ViewEventProcessorDependencies` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.ViewEventProcessorDependencies` | `LLM-spring-boot-starter-modules.md` |
| `AggregateIdSerializer` | java | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer` | `LLM-eventsourced-aggregates.md` |
| `StringValueTypeAggregateIdSerializer` | kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.StringValueTypeAggregateIdSerializer` | `LLM-kotlin-eventsourcing.md` |
| `GlobalEventOrder` | java+kotlin | `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder` | `LLM-foundation-types.md` |
| `MessageHandler` | java+kotlin | `dk.trustworks.essentials.components.foundation.messaging.MessageHandler` | `LLM-foundation-test.md` |
| `OrderedMessage` | java+kotlin | `dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage` | `LLM-components.md` |
| `RedeliveryPolicy` | java+kotlin | `dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy` | `LLM-foundation.md` |
| `RandomIdGenerator` | java+kotlin | `dk.trustworks.essentials.components.foundation.types.RandomIdGenerator` | `LLM-eventsourced-aggregates.md` |
| `AggregateTypeConfiguration` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.AggregateTypeConfiguration` | `LLM-eventsourced-aggregates.md` |
| `Decider` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.Decider` | `LLM-components.md` |
| `DeciderSupportsAggregateTypeChecker` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.DeciderSupportsAggregateTypeChecker` | `LLM-eventsourced-aggregates.md` |
| `Evolver` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.Evolver` | `LLM-components.md` |
| `DeciderAndAggregateTypeConfigurator` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.adapters.DeciderAndAggregateTypeConfigurator` | `LLM-eventsourced-aggregates.md` |
| `GivenWhenThenScenario` | kotlin | `dk.trustworks.essentials.components.kotlin.eventsourcing.test.GivenWhenThenScenario` | `LLM-eventsourced-aggregates.md` |
| `StringValueType` | kotlin | `dk.trustworks.essentials.kotlin.types.StringValueType` | `LLM-postgresql-document-db.md` |
| `EventBus` | java+kotlin | `dk.trustworks.essentials.reactive.EventBus` | `LLM-reactive.md` |
| `AnnotatedCommandHandler` | java+kotlin | `dk.trustworks.essentials.reactive.command.AnnotatedCommandHandler` | `LLM-reactive.md` |
| `CmdHandler` | java+kotlin | `dk.trustworks.essentials.reactive.command.CmdHandler` | `LLM-reactive.md` |
| `CommandBus` | java+kotlin | `dk.trustworks.essentials.reactive.command.CommandBus` | `LLM-components.md` |
| `CharSequenceType` | java | `dk.trustworks.essentials.types.CharSequenceType` | `LLM-foundation-test.md` |
| `Identifier` | java | `dk.trustworks.essentials.types.Identifier` | `LLM-components.md` |

## Notes

- **`EventHandler` is the one row whose FQCN the bundled docs do not state.**
  `LLM-eventsourced-aggregates.md` uses `@EventHandler` throughout its aggregate examples, so the
  annotation's existence and semantics are proven there, but no doc gives its package and no doc
  shows an import line for it. The package in the row above was read from upstream source —
  `examples/essentials-spring-examples/postgresql-cqrs/.../banking/aggregates/Account.java`, which
  imports `dk.trustworks.essentials.components.eventsourced.aggregates.EventHandler`. That is a
  stronger proof than a doc, not a weaker one, but it is recorded explicitly because it is the only
  row in this ledger not closed by `references/llm/` alone. If a future doc sync states the package,
  drop the warning marker.
- The **aggregate lane** (`AggregateRoot`, `StatefulAggregateRepository`,
  `StatefulAggregateInstanceFactory`) is the §R5 write style templated by
  `templates/java/{command_aggregate,bc-scaffold-aggregate}/`. It is a **Java** family: the Kotlin
  module ships no aggregate pattern, which is why `/essentials:slice-check` treats an `aggregates/`
  directory in a Kotlin bounded context as Advisory rather than as a lane it fully supports.
- `Decider` / `Evolver` / `AggregateTypeConfiguration` / `DeciderSupportsAggregateTypeChecker` /
  `DeciderAndAggregateTypeConfigurator` are the **Kotlin** event-sourcing family
  (`components.kotlin.eventsourcing`). `EventStreamDecider` / `EventStreamEvolver` /
  `EventStreamAggregateTypeConfiguration` / `EventStreamDeciderAndAggregateTypeConfigurator` are the
  **Java** family (`components.eventsourced.aggregates.eventstream`). They are different APIs and a
  template must never mix them — see `rules/slice-design.md` §R5.
- `ViewEventProcessor` takes **`ViewEventProcessorDependencies`**; plain `EventProcessor` and
  `InTransactionEventProcessor` take `EventProcessorDependencies`.
- The rebuild hook is
  `onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding)` —
  **there is no no-arg overload**, so a no-arg override does not compile. It fires once per subscribed
  `AggregateType`.
- The second `@MessageHandler` parameter (`OrderedMessage`) is **optional** to
  `PatternMatchingMessageHandler`. Templates take it where the handler needs `EventOrder` for
  idempotency, not because dispatch requires it.
- The **service-entity** templates (`command_service_entity/`, `view_service_entity/`,
  `bc-scaffold-service-entity/`) name only `EventBus`, `AnnotatedCommandHandler`, `CmdHandler` and
  `CommandBus` — the §R5 lane with no event store, so nothing from `components.eventsourced.*`
  appears in them. Handler registration is **not** written by those templates: it is done by
  `ReactiveHandlersBeanPostProcessor` (`LLM-reactive.md`), governed by the
  `reactive-bean-post-processor-enabled` property (`LLM-spring-boot-starter-modules.md`, default
  `true`). Note that `EssentialsComponentsConfiguration` and `EssentialsComponentsProperties` appear
  in **no** bundled doc — do not name either in a template, a skill, or a command.
- `@Entity`, `@Document`, `JpaRepository`, `MongoRepository` and `org.springframework.data.repository.Repository`
  are Spring, not Essentials, and are out of this ledger's scope — which is also why the
  service-entity view templates ship: they are persistence-neutral. The entity and its write
  repository are the only flavour-bound files on that lane, and **no template ships for them**
  (`slice-model.md` §3.5).
- `Version` has **no `of()` factory**. Kotlin uses the `Version(value)` constructor; Java uses the
  `long` overloads of `save`/`update` plus `Version.NOT_SAVED_YET_VALUE` / `Version.ZERO_VALUE`.
- Jackson (`@JsonTypeInfo`, `@JsonTypeName`) and Spring annotations are third-party, not Essentials,
  and are therefore out of this ledger's scope — but they are exercised by the shipped
  `tests/fixtures/worked-example/`.
