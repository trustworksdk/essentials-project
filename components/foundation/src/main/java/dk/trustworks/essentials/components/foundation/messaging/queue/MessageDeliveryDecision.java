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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What {@link MessageDeliveryClassifier} decided about a failed delivery, and enough of its reasoning that an
 * operator reading one log line can tell why.
 * <p>
 * Two classification rules changed at once in 0.60 — the {@code alwaysRetryOn} verdict started winning, and the
 * whole cause chain started being examined — so "my message stopped dead-lettering" has more than one candidate
 * explanation. {@link #describe()} is what makes the two distinguishable in a bug report.
 *
 * @param outcome                     what the consumer should do with the message
 * @param rule                        which rule produced {@code outcome}
 * @param matchedType                 the simple name of the exception type the rule matched, or {@code ""} when
 *                                    the rule matched no specific type
 * @param causeChainDepth             how deep in the cause chain {@code matchedType} was found — 0 is the thrown
 *                                    exception — or {@code -1} when no type was matched
 * @param totalDeliveryAttempts       the message's delivery attempts, including the one that just failed
 * @param maximumNumberOfRedeliveries the policy's redelivery cap
 */
public record MessageDeliveryDecision(MessageDeliveryOutcome outcome,
                                      MessageDeliveryRule rule,
                                      String matchedType,
                                      int causeChainDepth,
                                      int totalDeliveryAttempts,
                                      int maximumNumberOfRedeliveries) {

    public MessageDeliveryDecision {
        requireNonNull(outcome, "No outcome provided");
        requireNonNull(rule, "No rule provided");
        requireNonNull(matchedType, "No matchedType provided");
    }

    /**
     * @return true if the message should be marked as a Poison-Message/Dead-Letter-Message
     */
    public boolean isDeadLetter() {
        return outcome.isDeadLetter();
    }

    /**
     * A one-line explanation for the consumer's dead-letter log line: which rule fired, on what type, how deep
     * in the cause chain, and where the message stood against its redelivery cap.
     *
     * @return the explanation, e.g.
     * {@code "PERMANENT_ERROR (built-in permanent list matched IllegalArgumentException at cause-chain depth 3; attempt 1 of 6)"}
     */
    public String describe() {
        var explanation = switch (rule) {
            case POLICY_VERDICT -> "the RedeliveryPolicy's MessageDeliveryErrorHandler returned " + outcome;
            case BUILT_IN_PERMANENT_LIST -> "built-in permanent list matched " + matchedType
                    + " at cause-chain depth " + causeChainDepth;
            case POLICY_RETRY_OVERRIDE -> "the RedeliveryPolicy's MessageDeliveryErrorHandler overrode the "
                    + "built-in permanent list's " + matchedType + " match at cause-chain depth " + causeChainDepth;
            case REDELIVERY_CAP -> "no rule classified the error as permanent, and the redelivery cap is reached";
            case NONE -> "no rule classified the error as permanent";
        };
        return outcome + " (" + explanation + "; attempt " + totalDeliveryAttempts
                + " of " + (maximumNumberOfRedeliveries + 1) + ")";
    }

    /**
     * Which rule decided the {@link MessageDeliveryOutcome}.
     */
    public enum MessageDeliveryRule {
        /** The {@link dk.trustworks.essentials.components.foundation.messaging.MessageDeliveryErrorHandler} said so. */
        POLICY_VERDICT,
        /** The consumer's built-in list of always-permanent error types matched. */
        BUILT_IN_PERMANENT_LIST,
        /** The built-in list matched, but the handler explicitly asked to retry that type and was allowed to. */
        POLICY_RETRY_OVERRIDE,
        /** Nothing classified the error as permanent, but the message has used all its delivery attempts. */
        REDELIVERY_CAP,
        /** Nothing classified the error as permanent and attempts remain. */
        NONE
    }
}
