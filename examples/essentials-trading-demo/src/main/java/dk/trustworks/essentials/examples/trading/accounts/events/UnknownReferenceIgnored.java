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

package dk.trustworks.essentials.examples.trading.accounts.events;

import dk.trustworks.essentials.examples.trading.accounts.types.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A closing command — release a hold or shares, clear a deposit, settle or reverse a trade — named a reference this
 * generation never opened, and changed nothing. Recorded rather than silently ignored, because it is never a plain
 * duplicate (a close of an item opened and already closed stays a silent no-op): it is a command that arrived before
 * the one it closes, or one naming something that does not exist. Either way a later, unclosed item may be the result —
 * a hold never released, a trade never settled — so it must be visible. Changes no balance.
 * <p>
 * Known blind spot: a duplicate close redelivered after a book roll, for an item closed in the previous generation, is
 * reported here too, because the new generation does not carry closed items (design §5).
 */
public record UnknownReferenceIgnored(AccountGenerationId accountStreamId,
                                      AccountId accountId,
                                      IgnoredCommandKind kind,
                                      String reference) implements AccountEvent {
    public UnknownReferenceIgnored {
        requireNonNull(accountStreamId, "No accountStreamId provided");
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(kind, "No kind provided");
        requireNonNull(reference, "No reference provided");
    }
}
