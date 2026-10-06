package {{packagePath}}.{{bc}}.use_cases.{{slice}};

import {{packagePath}}.{{bc}}.types.{{Aggregate}}Id;

/**
 * Immutable {{Aggregate}} state, rebuilt from the event stream by {@link {{Aggregate}}StateEvolver}.
 *
 * PER-SLICE BY DEFAULT — it lives in THIS slice and holds only the fields THIS Decider reads.
 * Deciding from a state nobody else shares is the normal case, not a workaround.
 *
 * Do NOT move this to {@code use_cases/_shared/} until THREE OR MORE Deciders need the SAME state,
 * and none of them needs a field the others do not. Two is a coincidence. A shared State gives every
 * consumer a reason to edit it and drifts toward the union of everyone's needs — the god aggregate
 * one layer down (rules/slice-design.md § The `_shared/` promotion bar).
 *
 * Promotion is a plain MOVE: both type names stay, only the package changes. So waiting costs
 * nothing, and un-sharing later costs a lot.
 *
 * Many Deciders need no state at all — an idempotency check over the raw event list is enough.
 * Delete this file and its Evolver if that is true here.
 */
public record {{Aggregate}}State(
        {{Aggregate}}Id id,
        // TODO: the fields this BC's deciders need to decide
        String status
) {
}
