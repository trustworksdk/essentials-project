/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.function.Predicate;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Utility class providing predefined and composite implementations of {@code ClosingBooksDecisionPolicy}.
 * These policies determine the decision to keep a generation open, close it, or close and open the next
 * generation based on provided contexts, aggregates, and triggers.
 */
public final class ClosingBooksDecisionPolicies {
    private ClosingBooksDecisionPolicies() {
    }

    /**
     * Returns a policy that always decides to keep the current set of books open.
     * This policy ensures that no changes are made, and the current generation
     * remains active and operational.
     *
     * @return a policy that decides to keep the books open for a logical aggregate.
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> keepOpen() {
        return ignored -> ClosingBooksDecision.KEEP_OPEN;
    }

    /**
     * Returns a policy that always decides to close the current set of books.
     * This policy ensures that the books are finalized and transitioned to a closed state
     * without initiating any subsequent generation.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @return a policy that decides to close the books for a logical aggregate
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeOnly() {
        return ignored -> ClosingBooksDecision.CLOSE_ONLY;
    }

    /**
     * Returns a policy that always decides to close the current set of books and immediately open
     * the next set of books for a subsequent generation. This policy ensures a seamless transition
     * by both finalizing the current generation and starting a new one.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @return a policy that decides to close the current books and open the next for a logical aggregate
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeAndOpenNext() {
        return ignored -> ClosingBooksDecision.CLOSE_AND_OPEN_NEXT;
    }

    /**
     * Converts a legacy {@link ClosingBooksPolicy} into a {@link ClosingBooksDecisionPolicy}.
     * This allows for interoperability between the legacy policy interface and the newer decision-based policy.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @param policy      the legacy policy that determines whether a set of books should be closed
     * @return a decision-based closing-books policy derived from the provided legacy policy
     * @throws IllegalArgumentException if the provided policy is null
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> fromLegacyPolicy(ClosingBooksPolicy<ID, AGGREGATE> policy) {
        requireNonNull(policy, "No policy provided");
        return context -> policy.shouldCloseBooks(new ClosingBooksContext<>(context.aggregateType(),
                                                                           context.logicalAggregateId(),
                                                                           context.currentGeneration(),
                                                                           context.aggregate()))
                ? ClosingBooksDecision.CLOSE_AND_OPEN_NEXT
                : ClosingBooksDecision.KEEP_OPEN;
    }

    /**
     * Creates a decision-based closing-books policy that evaluates a given predicate on the
     * provided evaluation context. If the predicate test succeeds, the specified decision is
     * applied; otherwise, the policy defaults to keeping the current set of books open.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @param predicate   the condition used to evaluate the context and determine the decision
     * @param decision    the decision to apply when the predicate evaluates to true
     * @return a decision-making policy that evaluates the predicate and returns the specified decision or keeps the books open
     * @throws NullPointerException if the predicate or decision is null
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> when(Predicate<ClosingBooksEvaluationContext<ID, AGGREGATE>> predicate,
                                                                                  ClosingBooksDecision decision) {
        requireNonNull(predicate, "No predicate provided");
        requireNonNull(decision, "No decision provided");
        return context -> predicate.test(context) ? decision : ClosingBooksDecision.KEEP_OPEN;
    }

    /**
     * Creates a decision-based closing-books policy that evaluates a given predicate on the aggregate.
     * If the predicate test succeeds, the specified decision is applied; otherwise, the policy defaults
     * to keeping the books open.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @param predicate   the condition used to evaluate the aggregate and determine the decision
     * @param decision    the decision to apply when the predicate evaluates to true
     * @return a decision-making policy that evaluates the predicate and applies the specified decision
     * @throws NullPointerException if the predicate or decision is null
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeWhenAggregate(Predicate<AGGREGATE> predicate,
                                                                                                ClosingBooksDecision decision) {
        requireNonNull(predicate, "No predicate provided");
        requireNonNull(decision, "No decision provided");
        return when(context -> predicate.test(context.aggregate()), decision);
    }

    /**
     * Returns a decision-based closing-books policy that evaluates a given predicate on the aggregate.
     * If the predicate test succeeds, this policy decides to close the current set of books and immediately
     * open the next set for a subsequent generation. Otherwise, the policy defaults to keeping the books open.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @param predicate   the condition used to evaluate the aggregate and determine the decision
     * @return a decision-making policy that closes the current books and opens the next when the predicate evaluates to true
     * @throws NullPointerException if the predicate is null
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeAndOpenNextWhenAggregate(Predicate<AGGREGATE> predicate) {
        return closeWhenAggregate(predicate, ClosingBooksDecision.CLOSE_AND_OPEN_NEXT);
    }

    /**
     * Returns a decision-based closing-books policy that evaluates a given predicate on the aggregate.
     * If the predicate evaluates to true, this policy decides to close the current set of books without
     * initiating any subsequent generation. Otherwise, the policy defaults to keeping the books open.
     *
     * @param <ID>        the type of the identifier for the logical aggregate
     * @param <AGGREGATE> the type of the aggregate being evaluated
     * @param predicate   the condition used to evaluate the aggregate and determine the decision
     * @return a decision-making policy that closes the books when the predicate evaluates to true
     * @throws NullPointerException if the predicate is null
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeOnlyWhenAggregate(Predicate<AGGREGATE> predicate) {
        return closeWhenAggregate(predicate, ClosingBooksDecision.CLOSE_ONLY);
    }

    /**
     * Creates a {@code ClosingBooksDecisionPolicy} that applies a given decision when any of the specified trigger modes
     * are matched.
     *
     * @param <ID>        the type of the identifier for the aggregate.
     * @param <AGGREGATE> the type of the aggregate to which the decision policy applies.
     * @param decision    the decision to be applied when the policy is triggered.
     * @param triggerModes the trigger modes that activate the decision policy. At least one trigger mode must be provided.
     * @return a {@code ClosingBooksDecisionPolicy} that is triggered by the specified trigger modes and applies the given decision.
     * @throws NullPointerException if {@code decision} is null or if any of the {@code triggerModes} is null.
     * @throws IllegalArgumentException if no {@code triggerModes} are provided.
     */
    @SafeVarargs
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> whenTriggeredBy(ClosingBooksDecision decision,
                                                                                             ClosingBooksTriggerMode... triggerModes) {
        requireNonNull(decision, "No decision provided");
        requireNonNull(triggerModes, "No triggerModes provided");
        if (triggerModes.length == 0) {
            throw new IllegalArgumentException("At least one triggerMode must be provided");
        }
        var acceptedTriggerModes = Arrays.stream(triggerModes)
                                         .peek(triggerMode -> requireNonNull(triggerMode, "No triggerMode provided"))
                                         .collect(() -> EnumSet.noneOf(ClosingBooksTriggerMode.class),
                                                  EnumSet::add,
                                                  EnumSet::addAll);
        return when(context -> acceptedTriggerModes.contains(context.triggerMode()), decision);
    }

    /**
     * Combines multiple {@code ClosingBooksDecisionPolicy} instances into a single policy
     * that evaluates all provided policies. The combined policy will decide to
     * {@code KEEP_OPEN} if any of the individual policies decides to {@code KEEP_OPEN}.
     * Otherwise, it uses the most aggressive decision among the individual policies.
     *
     * @param <ID> the type of the identifier used in the decision context
     * @param <AGGREGATE> the type of the aggregate used in the decision context
     * @param policies the array of {@code ClosingBooksDecisionPolicy} instances to be combined.
     *                 Must not be null and must contain at least one policy.
     * @return a single {@code ClosingBooksDecisionPolicy} that combines the given policies
     *         and evaluates them as described.
     * @throws IllegalArgumentException if {@code policies} is null
     * @throws IllegalArgumentException if {@code policies} is empty
     */
    @SafeVarargs
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> allOf(ClosingBooksDecisionPolicy<ID, AGGREGATE>... policies) {
        requireNonNull(policies, "No policies provided");
        if (policies.length == 0) {
            throw new IllegalArgumentException("At least one policy must be provided");
        }
        return context -> {
            var decisions = Arrays.stream(policies)
                                  .filter(Objects::nonNull)
                                  .map(policy -> policy.decide(context))
                                  .toList();
            if (decisions.isEmpty() || decisions.stream().anyMatch(decision -> decision == ClosingBooksDecision.KEEP_OPEN)) {
                return ClosingBooksDecision.KEEP_OPEN;
            }
            return decisions.stream()
                            .reduce(ClosingBooksDecision.KEEP_OPEN, ClosingBooksDecisionPolicies::selectMoreAggressiveDecision);
        };
    }

    /**
     * Combines multiple {@code ClosingBooksDecisionPolicy} instances into a single policy
     * that applies any of the provided policies and makes a decision based on their outcomes.
     * If no policy recommends keeping books open, the most aggressive decision is selected.
     *
     * @param <ID> the type of the identifier used by the policy
     * @param <AGGREGATE> the type of the aggregate used by the policy
     * @param policies an array of {@code ClosingBooksDecisionPolicy} to combine
     * @return a {@code ClosingBooksDecisionPolicy} that evaluates the provided policies
     *         and combines their decisions
     * @throws IllegalArgumentException if the {@code policies} array is null
     * @throws IllegalArgumentException if no policies are provided
     */
    @SafeVarargs
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> anyOf(ClosingBooksDecisionPolicy<ID, AGGREGATE>... policies) {
        requireNonNull(policies, "No policies provided");
        if (policies.length == 0) {
            throw new IllegalArgumentException("At least one policy must be provided");
        }
        return context -> Arrays.stream(policies)
                                .filter(Objects::nonNull)
                                .map(policy -> policy.decide(context))
                                .filter(decision -> decision != ClosingBooksDecision.KEEP_OPEN)
                                .reduce(ClosingBooksDecision.KEEP_OPEN, ClosingBooksDecisionPolicies::selectMoreAggressiveDecision);
    }

    /**
     * Creates and returns a policy that triggers the closure of books and opens the next
     * set of books when accessed, based on the provided predicate.
     *
     * @param <ID>        The type of the identifier associated with the aggregate.
     * @param <AGGREGATE> The type of the aggregate the predicate operates on.
     * @param predicate   A predicate that determines when the closure and opening of the
     *                    next books should occur, based on the aggregate.
     * @return A {@code ClosingBooksDecisionPolicy} that defines the behavior for closing
     *         books and opening the next ones on access when the condition defined by the
     *         predicate is met.
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeAndOpenNextOnAccess(Predicate<AGGREGATE> predicate) {
        requireNonNull(predicate, "No predicate provided");
        return allOf(whenTriggeredBy(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT, ClosingBooksTriggerMode.ON_ACCESS),
                     closeAndOpenNextWhenAggregate(predicate));
    }

    /**
     * Creates a policy to close the current book and open the next one based on an explicit command
     * and a provided condition on the aggregate.
     *
     * @param <ID> The type of the identifier for the aggregate.
     * @param <AGGREGATE> The type of the aggregate on which the policy operates.
     * @param predicate A condition to be evaluated on the aggregate for triggering the decision to close
     *                  the current book and open the next one.
     * @return A {@code ClosingBooksDecisionPolicy} instance configured with the specified predicate
     *         and trigger mode.
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeAndOpenNextOnExplicitCommand(Predicate<AGGREGATE> predicate) {
        requireNonNull(predicate, "No predicate provided");
        return allOf(whenTriggeredBy(ClosingBooksDecision.CLOSE_AND_OPEN_NEXT, ClosingBooksTriggerMode.EXPLICIT_COMMAND),
                     closeAndOpenNextWhenAggregate(predicate));
    }

    /**
     * Creates a {@link ClosingBooksDecisionPolicy} that closes books only during a scheduled scan,
     * based on the provided predicate applied to the aggregate.
     *
     * @param <ID> the type of the identifier
     * @param <AGGREGATE> the type of the aggregate
     * @param predicate a {@link Predicate} applied to the aggregate to determine
     *                  conditions under which the decision policy should allow closing
     * @return a {@link ClosingBooksDecisionPolicy} configured to close books only
     *         during a scheduled scan and only if the predicate evaluates to true
     */
    public static <ID, AGGREGATE> ClosingBooksDecisionPolicy<ID, AGGREGATE> closeOnlyOnScheduledScan(Predicate<AGGREGATE> predicate) {
        requireNonNull(predicate, "No predicate provided");
        return allOf(whenTriggeredBy(ClosingBooksDecision.CLOSE_ONLY, ClosingBooksTriggerMode.SCHEDULED_SCAN),
                     closeOnlyWhenAggregate(predicate));
    }

    private static ClosingBooksDecision selectMoreAggressiveDecision(ClosingBooksDecision left,
                                                                     ClosingBooksDecision right) {
        if (left == ClosingBooksDecision.CLOSE_AND_OPEN_NEXT || right == ClosingBooksDecision.CLOSE_AND_OPEN_NEXT) {
            return ClosingBooksDecision.CLOSE_AND_OPEN_NEXT;
        }
        if (left == ClosingBooksDecision.CLOSE_ONLY || right == ClosingBooksDecision.CLOSE_ONLY) {
            return ClosingBooksDecision.CLOSE_ONLY;
        }
        return ClosingBooksDecision.KEEP_OPEN;
    }
}
