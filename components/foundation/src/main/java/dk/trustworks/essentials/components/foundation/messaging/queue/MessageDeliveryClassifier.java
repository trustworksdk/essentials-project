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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.MessageDeliveryDecision.MessageDeliveryRule;
import org.slf4j.*;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Decides what happens to a message whose handler threw: dead-letter it now, dead-letter it because its
 * delivery attempts are used up, or redeliver it.
 * <p>
 * This logic used to be copied verbatim into {@link DefaultDurableQueueConsumer} and
 * {@link CentralizedMessageFetcher}; both consumers now call this instead.
 *
 * <h2>The rules, in order</h2>
 * <ol>
 *   <li>The {@link RedeliveryPolicy}'s {@link MessageDeliveryErrorHandler} is asked for a
 *       {@link MessageDeliveryVerdict}. {@link MessageDeliveryVerdict#PERMANENT_ERROR} dead-letters the message
 *       immediately.</li>
 *   <li>The built-in list of always-permanent error types is matched against the cause chain. A match
 *       dead-letters the message — <em>unless</em> the handler answered {@link MessageDeliveryVerdict#RETRY}
 *       and the matched type is one the list allows to be overridden.</li>
 *   <li>Otherwise the {@link RedeliveryPolicy#maximumNumberOfRedeliveries} cap applies.</li>
 * </ol>
 *
 * <h2>What {@code RETRY} can and cannot override</h2>
 * A {@code RETRY} verdict must not be able to resurrect a failure that can never succeed, or a poison message
 * at the head of an ordered queue blocks everything behind it forever:
 * <table>
 *   <caption>The built-in permanent-error list</caption>
 *   <tr><th>Type</th><th>Overridable by {@code RETRY}</th><th>Why</th></tr>
 *   <tr><td>{@code DurableQueueDeserializationException}</td><td>No</td>
 *       <td>The stored bytes will not parse on the hundredth attempt either</td></tr>
 *   <tr><td>{@code MismatchedInputException}</td><td>No</td><td>Same</td></tr>
 *   <tr><td>{@link NoClassDefFoundError}</td><td>No</td>
 *       <td>A missing class is a deployment fault, not a transient one</td></tr>
 *   <tr><td>{@link IllegalArgumentException} (incl. {@link NumberFormatException})</td><td>Yes</td>
 *       <td>The house guard idiom, frequently thrown about data that may be valid later</td></tr>
 *   <tr><td>{@link ClassCastException}</td><td>Yes</td>
 *       <td>Usually a genuine bug, but a cast against a projection that has not caught up is legitimately
 *           transient, and an explicit {@code alwaysRetryOn} is a deliberate statement</td></tr>
 * </table>
 *
 * <h2>The whole cause chain is examined</h2>
 * Matching walks every link from the thrown exception to the root cause. It used to test only the two ends,
 * which made classification depend on whether a handler happened to attach a cause: a {@code @MessageHandler}
 * throw arrives wrapped ({@code UnitOfWorkException → ReflectionException → InvocationTargetException →
 * yours}), so the handler's own exception was normally the deepest and decided — but if it carried a cause of
 * its own, classification silently switched to that deeper type. Two handlers differing only in whether they
 * pass a cause got different dead-letter behaviour.
 */
public final class MessageDeliveryClassifier {
    private static final Logger log = LoggerFactory.getLogger(MessageDeliveryClassifier.class);

    /**
     * Jackson's {@code MismatchedInputException}, named rather than referenced: Jackson databind is an optional
     * dependency of this module, so an {@code instanceof} would fail to link on a runtime without it.
     */
    private static final Set<String> MISMATCHED_INPUT_CLASS_NAMES =
            Set.of("tools.jackson.databind.exc.MismatchedInputException");

    /**
     * Guards against a self-referencing cause chain, which {@link Throwable#initCause} does not prevent.
     */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 100;

    private MessageDeliveryClassifier() {
    }

    /**
     * Decide what to do with {@code queuedMessage} after its handler threw {@code error}.
     *
     * @param queuedMessage    the message whose delivery failed
     * @param error            the error the handler threw
     * @param redeliveryPolicy the policy in force for the consumer that was delivering the message
     * @return the decision, including why it was reached
     */
    public static MessageDeliveryDecision classify(QueuedMessage queuedMessage,
                                                   Throwable error,
                                                   RedeliveryPolicy redeliveryPolicy) {
        requireNonNull(queuedMessage, "No queuedMessage provided");
        requireNonNull(error, "No error provided");
        requireNonNull(redeliveryPolicy, "No redeliveryPolicy provided");

        var attempts = queuedMessage.getTotalDeliveryAttempts();
        var maxRedeliveries = redeliveryPolicy.getMaximumNumberOfRedeliveries();
        var verdict = redeliveryPolicy.verdict(queuedMessage, error);

        if (verdict == MessageDeliveryVerdict.PERMANENT_ERROR) {
            return decision(MessageDeliveryOutcome.PERMANENT_ERROR, MessageDeliveryRule.POLICY_VERDICT,
                            "", -1, attempts, maxRedeliveries);
        }

        var builtIn = findBuiltInPermanentError(error);
        if (builtIn.isPresent()) {
            var match = builtIn.get();
            if (verdict == MessageDeliveryVerdict.RETRY && match.overridable()) {
                // Fall through to the redelivery cap — the handler asked for this type to be retried.
                if (attempts < maxRedeliveries + 1) {
                    return decision(MessageDeliveryOutcome.RETRY, MessageDeliveryRule.POLICY_RETRY_OVERRIDE,
                                    match.type(), match.depth(), attempts, maxRedeliveries);
                }
                return decision(MessageDeliveryOutcome.REDELIVERIES_EXHAUSTED, MessageDeliveryRule.REDELIVERY_CAP,
                                match.type(), match.depth(), attempts, maxRedeliveries);
            }
            return decision(MessageDeliveryOutcome.PERMANENT_ERROR, MessageDeliveryRule.BUILT_IN_PERMANENT_LIST,
                            match.type(), match.depth(), attempts, maxRedeliveries);
        }

        if (attempts >= maxRedeliveries + 1) {
            return decision(MessageDeliveryOutcome.REDELIVERIES_EXHAUSTED, MessageDeliveryRule.REDELIVERY_CAP,
                            "", -1, attempts, maxRedeliveries);
        }
        return decision(MessageDeliveryOutcome.RETRY, MessageDeliveryRule.NONE, "", -1, attempts, maxRedeliveries);
    }

    private static MessageDeliveryDecision decision(MessageDeliveryOutcome outcome,
                                                    MessageDeliveryRule rule,
                                                    String matchedType,
                                                    int causeChainDepth,
                                                    int attempts,
                                                    int maxRedeliveries) {
        var decision = new MessageDeliveryDecision(outcome, rule, matchedType, causeChainDepth, attempts, maxRedeliveries);
        if (log.isDebugEnabled()) {
            log.debug("Classified delivery failure as {}", decision.describe());
        }
        return decision;
    }

    /**
     * Whether {@code error} is classified as permanent, ignoring the redelivery cap. Retained for callers that
     * only need the yes/no answer; {@link #classify} is what the consumers use.
     *
     * @param queuedMessage    the message whose delivery failed
     * @param error            the error the handler threw
     * @param redeliveryPolicy the policy in force for the consumer that was delivering the message
     * @return true if the message would be dead-lettered without consuming a delivery attempt
     */
    public static boolean isPermanentError(QueuedMessage queuedMessage,
                                           Throwable error,
                                           RedeliveryPolicy redeliveryPolicy) {
        return classify(queuedMessage, error, redeliveryPolicy).outcome() == MessageDeliveryOutcome.PERMANENT_ERROR;
    }

    /**
     * Walk the cause chain looking for a type on the built-in always-permanent list.
     *
     * @param error the error the handler threw
     * @return the first match, outermost first, or empty when the chain contains none
     */
    public static Optional<BuiltInPermanentError> findBuiltInPermanentError(Throwable error) {
        requireNonNull(error, "No error provided");

        var seen  = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var depth = 0;
        for (var current = error; current != null && depth < MAX_CAUSE_CHAIN_DEPTH; current = current.getCause()) {
            if (!seen.add(current)) {
                break;
            }
            var match = matchBuiltIn(current, depth);
            if (match.isPresent()) {
                return match;
            }
            depth++;
        }
        return Optional.empty();
    }

    private static Optional<BuiltInPermanentError> matchBuiltIn(Throwable candidate, int depth) {
        if (candidate instanceof DurableQueueDeserializationException) {
            return Optional.of(new BuiltInPermanentError("DurableQueueDeserializationException", depth, false));
        }
        if (isMismatchedInput(candidate)) {
            return Optional.of(new BuiltInPermanentError("MismatchedInputException", depth, false));
        }
        if (candidate instanceof NoClassDefFoundError) {
            return Optional.of(new BuiltInPermanentError("NoClassDefFoundError", depth, false));
        }
        if (candidate instanceof IllegalArgumentException) {
            return Optional.of(new BuiltInPermanentError(candidate.getClass().getSimpleName(), depth, true));
        }
        if (candidate instanceof ClassCastException) {
            return Optional.of(new BuiltInPermanentError("ClassCastException", depth, true));
        }
        return Optional.empty();
    }

    /**
     * Matches Jackson's {@code MismatchedInputException} and its subtypes by walking the candidate's own
     * superclass chain, which loads nothing that is not already loaded — unlike an {@code instanceof} against a
     * class that may not be on this runtime's classpath at all.
     */
    private static boolean isMismatchedInput(Throwable candidate) {
        for (Class<?> type = candidate.getClass(); type != null; type = type.getSuperclass()) {
            if (MISMATCHED_INPUT_CLASS_NAMES.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A type on the built-in always-permanent list, found in a failure's cause chain.
     *
     * @param type        the simple name of the matched type
     * @param depth       how deep in the cause chain it was found; 0 is the thrown exception
     * @param overridable whether a {@link MessageDeliveryVerdict#RETRY} verdict is allowed to override it
     */
    public record BuiltInPermanentError(String type, int depth, boolean overridable) {
        public BuiltInPermanentError {
            requireNonNull(type, "No type provided");
        }
    }
}
