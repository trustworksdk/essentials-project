package {{packagePath}}.{{bc}}.events;

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;

/**
 * Sealed event parent for the {{Bc}} bounded context (rules/slice-design.md §R3).
 *
 * §R3 applies on this lane **unchanged**: a sealed parent, one concrete variant per file, each owned
 * by its emitting slice. What differs is delivery — these events are published on the
 * {@code EventBus} as integration facts, never appended to a stream and never replayed.
 *
 * NO {@code @JsonTypeInfo} HERE, and that is deliberate. Here delivery is in-process and nothing is
 * serialised, so the annotation would be cargo. Add polymorphic typing only if these
 * events cross a **durable** boundary — and prefer translating to an explicit external type in a
 * translation slice instead, which keeps the internal hierarchy free to change.
 *
 * The serialisation trap on this lane is on the **command**, which is persisted in the durable-queue
 * table when sent with {@code sendAndDontWait}. See {@code references/llm/LLM-foundation.md}
 * § Commands are persisted.
 *
 * Java's {@code permits} clause may not be empty, so this file is emitted only with the BC's first
 * command slice, which supplies the first variant.
 *
 * NOTHING HERE MAY IMPORT A COMMAND TYPE (§R4). {@code events/} is the BC's importable surface, so a
 * command reference makes one slice's wire contract part of every foreign consumer's compile surface.
 *
 * {@code events/} and {@code types/} are the ONLY part of this bounded context other code may import.
 */
public sealed interface {{Aggregate}}Event
        permits {{Event}} {   // /essentials:add-slice appends each new variant to this list

    {{Aggregate}}Id id();
}
